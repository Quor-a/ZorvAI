package com.ai.assistance.quro.core.external

import android.content.Context

/**
 * 外部连接（模型服务组）：本机 OpenAI 兼容端点的密钥与调用记录。
 *
 * 外部工具（如桌面端 ChatBox、脚本）把本应用当作模型服务调用：
 * - Base URL：http://127.0.0.1:<port>/v1（端点随 MCP 前台服务启停）
 * - API 密钥：此处生成/吊销，服务端 Bearer 校验；未生成任何密钥时端点拒绝一切调用
 * - 最近使用：记录每次调用的来源密钥、时间、模型、token 估算，供「最近使用」列表展示
 *
 * 与 MCP 是两回事：MCP 暴露**工具**给本机客户端；外部连接暴露**对话补全**给外部工具。
 */
object QuroExternalAccessPrefs {

    private const val FILE = "quro_external_access"

    data class ApiKey(
        val id: String,
        val name: String,
        val key: String,
        val createdAt: Long,
        val lastUsedAt: Long = 0L,
        val callCount: Int = 0,
    )

    data class RecentCall(
        val keyId: String,
        val at: Long,
        val model: String,
        val promptTokens: Int,
        val completionTokens: Int,
    )

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 首帧读取端口用的 SharedPreferences 引用（由 [attach] 注入）。 */
    private var spHolder: android.content.SharedPreferences? = null

    /** 绑定 Context（Application 启动时调用一次），使端口流能在进程重启后恢复初值。 */
    fun attach(c: Context) {
        val prefs = sp(c)
        spHolder = prefs
        portFlow.value = prefs.getInt("port", 0)
    }

    // ── 总开关 ──
    fun isEnabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    /**
     * 灵魂注入开关（默认 **关**，保持 OpenAI 兼容端点的纯透传语义）。
     *
     * 打开后：外部请求**未自带 system 消息**时，把当前激活人格卡的身份 / 角色设定 /
     * Soul / 聊天设定 / 标签注入为 system（复用 QuroSoulPromptEngine，与 App 内聊天同源）。
     * 客户端自带 system 时一律不覆盖 —— 外部工具对自己发的提示词拥有最终解释权。
     * 不注入长期记忆 / 技能全文 / 工具清单，避免把用户隐私与设备能力暴露给局域网调用方。
     */
    fun isInjectPersona(c: Context) = sp(c).getBoolean("injectPersona", false)
    fun setInjectPersona(c: Context, v: Boolean) = sp(c).edit().putBoolean("injectPersona", v).apply()

    // ── 端口（由 QuroExternalServer 启动时写入） ──
    fun getPort(c: Context) = sp(c).getInt("port", 0)
    fun setPort(c: Context, port: Int) = sp(c).edit().putInt("port", port).apply()

    /**
     * 用户显式指定的自定义端口（外部连接页可改）。
     * 0 = 未设置。优先级高于「上次端口」与默认端口 —— 用户明确说要哪个就用哪个。
     */
    fun getCustomPort(c: Context) = sp(c).getInt("customPort", 0)
    fun setCustomPort(c: Context, port: Int) = sp(c).edit().putInt("customPort", port).apply()

    /**
     * 端口的**可观察**版本（供 UI 实时跟随服务启停）。
     *
     * 🔴 此前 UI 用 `remember { getPort(ctx) }` **一次性**读取端口 —— 开关打开后
     * 服务在后台才真正起来并写入端口，UI 的 remember 早已固化 → 页面永远显示
     * 「服务未运行 / —」，用户看到的就是"外部连接根本不能用"。
     * 现在改成 StateFlow：服务每次写端口都推一次，UI 直接 collect。
     */
    private val portFlow = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** 端口状态流：服务启停时立刻更新，UI 不用猜。 */
    fun portFlow(): kotlinx.coroutines.flow.StateFlow<Int> = portFlow

    /** 写端口并广播（服务启停的唯一入口）。 */
    fun publishPort(c: Context, port: Int) {
        sp(c).edit().putInt("port", port).apply()
        portFlow.value = port
    }

    /**
     * 一个可被外部调用路由到的上游接入点。
     * 来源有二：当前活跃模型配置（QuroModelConfigRepository）与已保存预设（QuroSavedProfileRepository）。
     */
    data class UpstreamConfig(
        val name: String = "",
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val provider: String = "OPENAI",
        val temperature: Float = 0.7f,
        val maxTokens: Int = 2048,
    )

    /** 当前活跃模型配置 → 上游接入点。 */
    fun activeConfig(c: Context): UpstreamConfig = runCatching {
        val cfg = com.ai.assistance.quro.core.model.QuroModelConfigRepository(c).load()
        UpstreamConfig(
            baseUrl = cfg.baseUrl, apiKey = cfg.apiKey, model = cfg.model,
            provider = cfg.provider, temperature = cfg.temperature, maxTokens = cfg.maxTokens,
        )
    }.getOrDefault(UpstreamConfig(baseUrl = "https://api.openai.com/v1", apiKey = "", model = "zorv-ai"))

    /** 全部已保存预设 → 上游接入点列表（model 为空的预设跳过）。 */
    fun savedProfiles(c: Context): List<UpstreamConfig> = runCatching {
        com.ai.assistance.quro.core.model.QuroSavedProfileRepository(c).loadAll()
            .filter { it.model.isNotBlank() && it.baseUrl.isNotBlank() }
            .map {
                UpstreamConfig(
                    name = it.name, baseUrl = it.baseUrl, apiKey = it.apiKey, model = it.model,
                    provider = it.provider, temperature = it.temperature, maxTokens = it.maxTokens,
                )
            }
    }.getOrDefault(emptyList())

    /**
     * 可供外部调用的模型选项（= 当前活跃配置 + 全部已保存预设）。
     *
     * 这就是「怎么拉已经保存的模型」的数据源：外部连接页直接列出它们，
     * 用户勾选哪些暴露给外部客户端，外部客户端的模型下拉里就能看到哪些。
     */
    data class ModelOption(
        /** 外部客户端看到的 model id。 */
        val id: String,
        /** 展示名（预设名，缺省用 model id）。 */
        val label: String,
        /** 来源：当前配置 / 预设。 */
        val source: String,
        /** baseUrl 主机（用于区分同名 model 的不同接入点）。 */
        val host: String,
    )

    /** 已保存的模型选项列表（按 model id 去重，保持顺序）。 */
    fun modelOptions(c: Context): List<ModelOption> {
        val out = LinkedHashMap<String, ModelOption>()
        runCatching {
            val active = activeConfig(c)
            if (active.model.isNotBlank()) {
                out[active.model] = ModelOption(
                    id = active.model, label = "当前配置", source = "当前配置",
                    host = hostOf(active.baseUrl),
                )
            }
            savedProfiles(c).forEach { p ->
                if (!out.containsKey(p.model)) {
                    out[p.model] = ModelOption(
                        id = p.model,
                        label = p.name.ifBlank { p.model },
                        source = "预设",
                        host = hostOf(p.baseUrl),
                    )
                }
            }
        }
        return out.values.toList()
    }

    /** baseUrl → 主机名（"https://api.x.com/v1" → "api.x.com"）。 */
    private fun hostOf(baseUrl: String): String =
        baseUrl.substringAfter("//").substringBefore('/').ifBlank { "—" }

    /**
     * 用户勾选要暴露给外部的模型 id 集合。
     *
     * 空集 = 全部暴露（默认行为）。非空时 `/v1/models` 只返回勾中的那些。
     */
    fun exposedModels(c: Context): Set<String> {
        val raw = sp(c).getString("exposed_models", null) ?: return emptySet()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
        }.getOrDefault(emptySet())
    }

    /** 保存勾选结果（空集 = 全部暴露）。 */
    fun setExposedModels(c: Context, ids: Set<String>) {
        val arr = org.json.JSONArray()
        ids.forEach { arr.put(it) }
        sp(c).edit().putString("exposed_models", arr.toString()).apply()
    }

    // ── 密钥 ──
    fun keys(c: Context): List<ApiKey> {
        val raw = sp(c).getString("keys", null) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ApiKey(
                    id = o.optString("id"),
                    name = o.optString("name", "密钥"),
                    key = o.optString("key"),
                    createdAt = o.optLong("createdAt"),
                    lastUsedAt = o.optLong("lastUsedAt"),
                    callCount = o.optInt("callCount"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveKeys(c: Context, list: List<ApiKey>) {
        val arr = org.json.JSONArray()
        list.forEach { k ->
            arr.put(
                org.json.JSONObject()
                    .put("id", k.id).put("name", k.name).put("key", k.key)
                    .put("createdAt", k.createdAt).put("lastUsedAt", k.lastUsedAt)
                    .put("callCount", k.callCount)
            )
        }
        sp(c).edit().putString("keys", arr.toString()).apply()
    }

    /** 生成新密钥（sk- 前缀 + 32 位随机十六进制），返回新列表。 */
    fun createKey(c: Context, name: String): List<ApiKey> {
        val key = "sk-" + buildString {
            val rnd = java.security.SecureRandom()
            repeat(32) { append("0123456789abcdef"[rnd.nextInt(16)]) }
        }
        val list = keys(c) + ApiKey(
            id = "k${System.currentTimeMillis()}",
            name = name.ifBlank { "密钥 ${keys(c).size + 1}" },
            key = key,
            createdAt = System.currentTimeMillis(),
        )
        saveKeys(c, list)
        return list
    }

    fun revokeKey(c: Context, id: String): List<ApiKey> {
        val list = keys(c).filterNot { it.id == id }
        saveKeys(c, list)
        return list
    }

    /** Bearer 校验：命中返回密钥，未命中 null。同时累计调用计数与最近使用时间。 */
    fun authenticate(c: Context, authHeader: String?): ApiKey? {
        val token = authHeader
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim() ?: return null
        val hit = keys(c).firstOrNull { it.key == token } ?: return null
        val updated = hit.copy(lastUsedAt = System.currentTimeMillis(), callCount = hit.callCount + 1)
        saveKeys(c, keys(c).map { if (it.id == hit.id) updated else it })
        return updated
    }

    // ── 最近使用（环形，最多 50 条）──
    fun recentCalls(c: Context): List<RecentCall> {
        val raw = sp(c).getString("recent", null) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                RecentCall(
                    keyId = o.optString("keyId"),
                    at = o.optLong("at"),
                    model = o.optString("model"),
                    promptTokens = o.optInt("promptTokens"),
                    completionTokens = o.optInt("completionTokens"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun recordCall(c: Context, call: RecentCall) {
        val list = (listOf(call) + recentCalls(c)).take(50)
        val arr = org.json.JSONArray()
        list.forEach { r ->
            arr.put(
                org.json.JSONObject()
                    .put("keyId", r.keyId).put("at", r.at).put("model", r.model)
                    .put("promptTokens", r.promptTokens).put("completionTokens", r.completionTokens)
            )
        }
        sp(c).edit().putString("recent", arr.toString()).apply()
    }
}
