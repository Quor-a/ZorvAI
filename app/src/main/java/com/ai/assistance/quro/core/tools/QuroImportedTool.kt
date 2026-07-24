package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 可导入的工具：用户从「+」面板粘贴 JSON 导入，或由 AI 自写后插入。
 * 导入后持久化到应用私有目录 imported_tools.json，并在每次 buildQuroRegistry() 时并入运行时注册表，
 * 从而「导入成功自动成为可调用工具」（AI 默认可见并可执行）。
 *
 * 支持的执行类型（kind）：
 * - "http"     ：按 config 固定发起 HTTP 请求（复用项目已引入的 OkHttp，Apache-2.0）。
 * - "intent"   ：构造 Intent 并 startActivity（携带 FLAG_ACTIVITY_NEW_TASK，可在后台触发）。
 * - "broadcast"：sendBroadcast 发送广播。
 */
data class ImportedToolDef(
    val name: String,
    val description: String,
    val parametersJson: String,
    val kind: String, // http | intent | broadcast
    val config: String, // 对应类型的 JSON 配置
)

class QuroImportedTool(private val def: ImportedToolDef) : QuroTool {
    override val name = def.name
    override val description = def.description
    override val parametersJson = def.parametersJson.ifBlank {
        """{"type":"object","properties":{"arg":{"type":"string","description":"可选参数"}}}"""
    }

    override fun run(context: Context, arguments: String): String {
        return when (def.kind) {
            "http" -> runHttp(arguments)
            "intent" -> runIntent(context, arguments)
            "broadcast" -> runBroadcast(context, arguments)
            else -> "不支持的导入工具类型: ${def.kind}（仅支持 http / intent / broadcast）"
        }
    }

    private fun runHttp(arguments: String): String {
        val cfg = runCatching { JSONObject(def.config) }.getOrElse { return "工具配置不是合法 JSON: ${def.config}" }
        val baseUrl = cfg.optString("url", "").trim()
        if (baseUrl.isEmpty()) return "http 工具缺少 url 配置"
        val method = cfg.optString("method", "GET").uppercase()
        val headersStr = cfg.optString("headers", "")
        val bodyStr = cfg.optString("body", "")
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        var url = baseUrl
        val q = jo.optString("query", "").trim()
        if (q.isNotEmpty()) url += if (url.contains("?")) "&$q" else "?$q"
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).build()
        return try {
            val reqBuilder = Request.Builder().url(url)
            if (headersStr.isNotBlank()) {
                val h = runCatching { JSONObject(headersStr) }.getOrNull()
                h?.keys()?.forEach { k -> reqBuilder.addHeader(k, h.optString(k)) }
            }
            val reqBody = if (bodyStr.isNotEmpty() && method != "GET" && method != "HEAD") {
                bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType())
            } else null
            val request = reqBuilder.method(method, reqBody).build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val truncated = if (body.length > 8000) body.take(8000) + "\n...[截断]" else body
                "HTTP ${resp.code} ${resp.message}\n$truncated"
            }
        } catch (e: Exception) {
            "http 工具执行失败: ${e.message}"
        }
    }

    private fun runIntent(context: Context, arguments: String): String {
        val cfg = runCatching { JSONObject(def.config) }.getOrElse { return "工具配置不是合法 JSON: ${def.config}" }
        val action = cfg.optString("action", "").trim()
        val data = cfg.optString("data", "").trim()
        val type = cfg.optString("type", "").trim()
        val pkg = cfg.optString("package", "").trim()
        val cls = cfg.optString("component", "").trim()
        val extrasStr = cfg.optString("extras", "")
        val intent = Intent()
        if (action.isNotEmpty()) intent.action = action
        if (data.isNotEmpty()) intent.data = Uri.parse(data)
        if (type.isNotEmpty()) intent.type = type
        if (cls.isNotEmpty()) {
            val parts = cls.split("/", limit = 2)
            val p = if (parts.size == 2) parts[0] else pkg.ifEmpty { context.packageName }
            val c = if (parts.size == 2) parts[1] else cls
            intent.component = android.content.ComponentName(p, c)
        } else if (pkg.isNotEmpty()) {
            intent.`package` = pkg
        }
        if (extrasStr.isNotBlank()) {
            val ex = runCatching { JSONObject(extrasStr) }.getOrNull()
            ex?.keys()?.forEach { k -> intent.putExtra(k, ex.optString(k)) }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "已启动 Intent：${action.ifEmpty { cls.ifEmpty { data } }}"
        } catch (e: Exception) {
            "启动 Intent 失败: ${e.message}"
        }
    }

    private fun runBroadcast(context: Context, arguments: String): String {
        val cfg = runCatching { JSONObject(def.config) }.getOrElse { return "工具配置不是合法 JSON: ${def.config}" }
        val action = cfg.optString("action", "").trim()
        if (action.isEmpty()) return "broadcast 工具缺少 action 配置"
        val extrasStr = cfg.optString("extras", "")
        val intent = Intent(action)
        if (extrasStr.isNotBlank()) {
            val ex = runCatching { JSONObject(extrasStr) }.getOrNull()
            ex?.keys()?.forEach { k -> intent.putExtra(k, ex.optString(k)) }
        }
        return try {
            context.sendBroadcast(intent)
            "已发送广播：$action"
        } catch (e: Exception) {
            "发送广播失败: ${e.message}"
        }
    }
}

/** 持久化导入工具注册表（单例），存于 context.filesDir/imported_tools.json。 */
object QuroImportedToolRegistry {
    private const val FILE = "imported_tools.json"
    private val list = mutableListOf<ImportedToolDef>()

    fun load(context: Context) {
        list.clear()
        runCatching {
            val f = java.io.File(context.filesDir, FILE)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        ImportedToolDef(
                            name = o.getString("name"),
                            description = o.optString("description", ""),
                            parametersJson = o.optString("parametersJson", ""),
                            kind = o.optString("kind", "http"),
                            config = o.optString("config", "{}"),
                        )
                    )
                }
            }
        }
    }

    fun all(): List<ImportedToolDef> = list.toList()
    fun tools(): List<QuroImportedTool> = list.map { QuroImportedTool(it) }

    fun add(context: Context, def: ImportedToolDef) {
        list.removeIf { it.name == def.name }
        list.add(def)
        save(context)
    }

    fun remove(context: Context, name: String) {
        list.removeIf { it.name == name }
        save(context)
    }

    fun contains(name: String) = list.any { it.name == name }

    private fun save(context: Context) {
        runCatching {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject().apply {
                        put("name", it.name)
                        put("description", it.description)
                        put("parametersJson", it.parametersJson)
                        put("kind", it.kind)
                        put("config", it.config)
                    }
                )
            }
            java.io.File(context.filesDir, FILE).writeText(arr.toString(2))
        }
    }
}
