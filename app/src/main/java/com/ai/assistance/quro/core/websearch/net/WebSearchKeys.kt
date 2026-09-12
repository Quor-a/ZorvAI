package com.ai.assistance.quro.core.websearch.net

import android.content.Context
import android.content.SharedPreferences

/**
 * WebSearchKeys —— 联网检索可选 API 凭据的本地存储。
 *
 * 设计原则（数据不出设备）：
 * 1. 凭据只存本机 SharedPreferences（MODE_PRIVATE），绝不走网络、绝不外传；
 * 2. 当前仅 Brave Search API Key 使用；不填则 BraveEngine 自动跳过，不影响其余引擎；
 * 3. 通过 [init] 在 Application.onCreate 注入 Context，未初始化时读取安全降级为空串。
 *
 * 与 weixin_clawbot AccountStore 同一套单例 + 独立 PREFS_NAME 约定，便于后续扩展更多 key。
 */
object WebSearchKeys {

    private const val PREFS_NAME = "websearch_keys"
    private const val KEY_BRAVE = "brave_api_key"

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

    /** Brave Search API Key；未配置或尚未初始化时返回空串。 */
    var braveKey: String
        get() = if (::prefs.isInitialized) prefs.getString(KEY_BRAVE, "").orEmpty().trim() else ""
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putString(KEY_BRAVE, value.trim()).apply()
            }
        }

    /** 是否已配置可用的 Brave key（供 UI / 自检判断）。 */
    fun hasBraveKey(): Boolean = braveKey.isNotBlank()
}
