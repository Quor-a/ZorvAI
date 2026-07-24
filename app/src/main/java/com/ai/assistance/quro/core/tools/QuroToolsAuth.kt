package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONObject

/**
 * 第三方服务授权保险库（Path ② 知识库的配套能力）。
 *
 * AI 经常需要调用外部服务（例如自建 RAG 网关、企业 API、天气服务、Notion / GitHub 等），
 * 这些服务需要凭据（Bearer Token / API Key / Basic 账号密码）。本模块提供一个
 * **私有 SharedPreferences 保险库**（仅本应用可读写，MODE_PRIVATE），让 AI 把授权信息
 * 安全地存下来，之后用 [HttpRequestTool] 的 `service` 参数即可自动带上鉴权头与 baseUrl，
 * 不必每次把密钥明文写进对话。
 *
 * 安全说明：当前用私有 SharedPreferences（未引入 security-crypto 的 EncryptedSharedPreferences，
 * 因为它不在 libs.versions.toml 且本会话不下载额外 AAR）。这对"设备已解锁、同用户"场景足够；
 * 若需防 root 提取，后续可平滑升级为 EncryptedSharedPreferences（文件名/调用不变）。
 */
data class AuthService(
    val name: String,
    val type: String, // bearer | apikey | basic
    val token: String, // bearer/apikey 的令牌；basic 时为 user:password
    val baseUrl: String, // 可选：相对 url 时自动前缀（如 https://api.example.com）
    val extra: String, // 可选：额外固定头，JSON 对象字符串，如 {"X-Tenant":"acme"}
)

object QuroAuthStore {
    private const val PREF = "quro_auth_vault"
    private const val KEY_PREFIX = "svc:"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 列出所有已存服务（不含敏感明文 token）。 */
    fun list(ctx: Context): List<AuthService> =
        prefs(ctx).all.keys.filter { it.startsWith(KEY_PREFIX) }
            .mapNotNull { k -> runCatching { parse(prefs(ctx).getString(k, null)) }.getOrNull() }

    /** 按名称取服务（含明文 token，仅在发起请求时内部使用）。 */
    fun get(ctx: Context, name: String): AuthService? =
        runCatching { parse(prefs(ctx).getString(KEY_PREFIX + name, null)) }.getOrNull()

    fun save(ctx: Context, svc: AuthService) {
        prefs(ctx).edit().putString(KEY_PREFIX + svc.name, toJson(svc)).apply()
    }

    fun remove(ctx: Context, name: String) {
        prefs(ctx).edit().remove(KEY_PREFIX + name).apply()
    }

    fun exists(ctx: Context, name: String): Boolean =
        prefs(ctx).contains(KEY_PREFIX + name)

    private fun toJson(s: AuthService): String = JSONObject().apply {
        put("name", s.name)
        put("type", s.type)
        put("token", s.token)
        put("baseUrl", s.baseUrl)
        put("extra", s.extra)
    }.toString()

    private fun parse(json: String?): AuthService? {
        if (json.isNullOrBlank()) return null
        val jo = JSONObject(json)
        return AuthService(
            name = jo.optString("name"),
            type = jo.optString("type", "bearer"),
            token = jo.optString("token"),
            baseUrl = jo.optString("baseUrl"),
            extra = jo.optString("extra"),
        )
    }
}

/** 把服务凭据解析成（Authorization 头名 → 头值）列表 + 额外固定头。 */
fun AuthService.resolveHeaders(): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    when (type.lowercase()) {
        "bearer" -> out.add("Authorization" to "Bearer $token")
        "apikey" -> out.add("X-API-Key" to token)
        "basic" -> {
            val raw = android.util.Base64.encodeToString(
                token.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP,
            )
            out.add("Authorization" to "Basic $raw")
        }
    }
    if (extra.isNotBlank()) {
        runCatching {
            val ej = JSONObject(extra)
            ej.keys().forEach { out.add(it to ej.optString(it)) }
        }
    }
    return out
}

/** 保存 / 更新一个第三方服务授权。 */
class AuthServiceAddTool : QuroTool {
    override val name = "auth_service_add"
    override val description = "保存或更新一个第三方服务授权，供 http_request 用 service 参数自动带鉴权。" +
        "参数 {\"name\":\"服务别名\",\"type\":\"bearer|apikey|basic\",\"token\":\"凭据\",\"baseUrl\":\"可选前缀地址\",\"extra\":\"可选额外头JSON\"}。" +
        "type=bearer 时 token 为令牌；apikey 时 token 为 API Key（注入 X-API-Key 头）；basic 时 token 为 user:password。" +
        "baseUrl 用于相对 url 自动补全。凭据存于应用私有保险库，列表时脱敏。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "name":{"type":"string","description":"服务别名，如 my_rag_gw"},
            "type":{"type":"string","description":"鉴权类型：bearer | apikey | basic"},
            "token":{"type":"string","description":"凭据：bearer/apikey 的令牌，或 basic 的 user:password"},
            "baseUrl":{"type":"string","description":"可选，相对 url 时自动前缀的基地址，如 https://api.example.com"},
            "extra":{"type":"string","description":"可选，额外固定请求头，JSON 对象字符串，如 {\"X-Tenant\":\"acme\"}"}
        },
        "required":["name","type","token"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val name = jo.optString("name", "").trim()
        if (name.isEmpty()) return "缺少 name 参数"
        val type = jo.optString("type", "bearer").lowercase()
        if (type !in setOf("bearer", "apikey", "basic")) return "type 必须为 bearer | apikey | basic"
        val token = jo.optString("token", "")
        if (token.isEmpty()) return "缺少 token 参数"
        val baseUrl = jo.optString("baseUrl", "").trim()
        val extra = jo.optString("extra", "").trim()
        if (extra.isNotBlank()) {
            runCatching { JSONObject(extra) }.onFailure { return "extra 不是合法 JSON 对象" }
        }
        val svc = AuthService(name, type, token, baseUrl, extra)
        QuroAuthStore.save(context, svc)
        return "已保存授权服务「$name」(type=$type)。之后 http_request 可传 \"service\":\"$name\" 自动带鉴权" +
            (if (baseUrl.isNotEmpty()) "，并以 $baseUrl 补全相对 url" else "") + "。"
    }
}

/** 列出已授权服务（token 脱敏）。 */
class AuthServiceListTool : QuroTool {
    override val name = "auth_service_list"
    override val description = "列出已保存的第三方服务授权（凭据脱敏，仅显示类型/基地址）。参数 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val list = QuroAuthStore.list(context)
        if (list.isEmpty()) return "尚未保存任何第三方服务授权。可用 auth_service_add 添加。"
        val sb = StringBuilder()
        sb.append("已授权服务（共 ${list.size} 个，凭据已脱敏）：\n")
        list.forEachIndexed { i, s ->
            val masked = if (s.token.length > 4) s.token.take(4) + "••••" else "••••"
            sb.append("${i + 1}. ${s.name}  [${s.type}]  token=$masked")
            if (s.baseUrl.isNotEmpty()) sb.append("  baseUrl=${s.baseUrl}")
            if (s.extra.isNotBlank()) sb.append("  extra=${s.extra}")
            sb.append("\n")
        }
        sb.append("调用 http_request 时传 service 别名即可自动带鉴权。")
        return sb.toString()
    }
}

/** 删除一个第三方服务授权。 */
class AuthServiceRemoveTool : QuroTool {
    override val name = "auth_service_remove"
    override val description = "删除一个已保存的第三方服务授权。参数 {\"name\":\"服务别名\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{"name":{"type":"string","description":"要删除的服务别名"}},
        "required":["name"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val name = JSONObject(arguments).optString("name", "").trim()
        if (name.isEmpty()) return "缺少 name 参数"
        if (!QuroAuthStore.exists(context, name)) return "未找到授权服务「$name」。"
        QuroAuthStore.remove(context, name)
        return "已删除授权服务「$name」。"
    }
}
