package com.ai.assistance.quro.core.websearch.net

import android.content.Context
import android.content.SharedPreferences

/**
 * WebSearchKeys —— 联网检索可选 API 凭据与配置的本地存储。
 *
 * 设计原则（数据不出设备）：
 * 1. 凭据只存本机 SharedPreferences（MODE_PRIVATE），绝不走网络、绝不外传；
 * 2. 各提供方（Brave / Kagi / Serper / Tavily / SearXNG 自建地址）独立存储，未配置则对应引擎自动跳过；
 * 3. 通过 [init] 在 Application.onCreate 注入 Context，未初始化时读取安全降级为空串。
 *
 * 与 weixin_clawbot AccountStore 同一套单例 + 独立 PREFS_NAME 约定。
 * 「可切换搜索服务提供方」架构：
 * - [webSearchEnabled] 联网总开关；
 * - [resultCount] 结果条数（1-10，进 WebSearchOrchestrator.readTopK 相关逻辑）；
 * - [searxngUrl] 自建 SearXNG 实例地址（可填）。
 */
object WebSearchKeys {

    private const val PREFS_NAME = "websearch_keys"
    private const val KEY_BRAVE = "brave_api_key"
    private const val KEY_KAGI = "kagi_api_key"
    private const val KEY_SERPER = "serper_api_key"
    private const val KEY_TAVILY = "tavily_api_key"
    private const val KEY_SEARXNG_URL = "searxng_url"
    private const val KEY_BOTHUB = "bothub_api_key"
    private const val KEY_ENABLED = "websearch_enabled"
    private const val KEY_RESULT_COUNT = "websearch_result_count"

    @Volatile
    private var instance: WebSearchKeys? = null

    private lateinit var prefs: SharedPreferences

    /** 在 Application.onCreate 调用一次；重复调用安全（仅首调生效）。 */
    fun init(context: Context) {
        if (instance == null) {
            synchronized(this) {
                if (instance == null) {
                    prefs = context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    instance = this
                }
            }
        }
    }

    private fun string(key: String): String =
        if (::prefs.isInitialized) prefs.getString(key, "").orEmpty().trim() else ""

    private fun bool(key: String, default: Boolean): Boolean =
        if (::prefs.isInitialized) prefs.getBoolean(key, default) else default

    private fun int(key: String, default: Int): Int =
        if (::prefs.isInitialized) prefs.getInt(key, default).coerceIn(1, 10) else default

    private fun write(key: String, value: Any) {
        if (::prefs.isInitialized) {
            prefs.edit().apply {
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                }
            }.apply()
        }
    }

    /** Brave Search API Key；未配置或尚未初始化时返回空串。 */
    var braveKey: String
        get() = string(KEY_BRAVE)
        set(value) { write(KEY_BRAVE, value.trim()) }

    /** Kagi Search API Key（需付费 Kagi 账号）。 */
    var kagiKey: String
        get() = string(KEY_KAGI)
        set(value) { write(KEY_KAGI, value.trim()) }

    /** Serper.dev API Key（Google 结果的商业中转）。 */
    var serperKey: String
        get() = string(KEY_SERPER)
        set(value) { write(KEY_SERPER, value.trim()) }

    /** Tavily Search API Key。 */
    var tavilyKey: String
        get() = string(KEY_TAVILY)
        set(value) { write(KEY_TAVILY, value.trim()) }

    /** OpenAI 兼容网关 API Key（驱动 LLM 搜索引擎）。 */
    var bothubKey: String
        get() = string(KEY_BOTHUB)
        set(value) { write(KEY_BOTHUB, value.trim()) }

    /** 自建 SearXNG 实例地址（可选）；空 = 该引擎自动跳过。 */
    var searxngUrl: String
        get() = string(KEY_SEARXNG_URL)
        set(value) { write(KEY_SEARXNG_URL, value.trim()) }

    /** 联网总开关（默认开：保持既有「默认联网可用」行为）。 */
    var webSearchEnabled: Boolean
        get() = bool(KEY_ENABLED, true)
        set(value) { write(KEY_ENABLED, value) }

    /** 结果条数（1-10），默认 5，与 WebSearchProvider.readTopK 默认一致。 */
    var resultCount: Int
        get() = int(KEY_RESULT_COUNT, 5)
        set(value) { write(KEY_RESULT_COUNT, value.coerceIn(1, 10)) }

    /** 是否已配置可用的 Brave key（供 UI / 自检判断）。 */
    fun hasBraveKey(): Boolean = braveKey.isNotBlank()

    /** 是否配置了任一付费/自建提供方 key（用于设置页汇总提示）。 */
    fun configuredProviderCount(): Int =
        listOf(braveKey, kagiKey, serperKey, tavilyKey, searxngUrl, bothubKey).count { it.isNotBlank() }
}
