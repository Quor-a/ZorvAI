package com.ai.assistance.quro.etalib.config

import android.content.Context
import android.content.SharedPreferences
import com.ai.assistance.quro.core.xposed.QuroModuleConfig
import com.ai.assistance.quro.core.xposed.QuroModulePrefs

/**
 * Eta hook 层的开关读取外观。
 *
 * ZorvAI 的版本：所有开关都落在 ZorvAI 自己的 `QuroModulePrefs` / `QuroModuleConfig`
 * （LSPosed RemotePreferences，UI 可写、Hook 只读），不再维护第二套 Eta 配置体系。
 * 本对象只做键名映射，保证 Eta hook 层源码无需改动。
 */
internal object Prefs {

    /** 直接复用 ZorvAI 自己的 RemotePreferences 组名。 */
    const val GROUP: String = QuroModuleConfig.PREFS_GROUP

    object Keys {
        const val POWER_KEY_ASSISTANT_TARGET = QuroModuleConfig.Keys.POWER_ASSISTANT_TARGET
        const val POWER_KEY_TAKEOVER = "power_key_takeover"
        const val ASSISTANT_AUTO_CONFIG = QuroModuleConfig.Keys.AUTO_DEFAULT_ASSISTANT
        const val HOTWORD_SELF_HEAL = QuroModuleConfig.Keys.HOTWORD_SELF_HEAL
        const val GESTURE_BAR_CIRCLE_TO_SEARCH = QuroModuleConfig.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH
        const val DOUBLE_FINGER_CIRCLE_TO_SEARCH = "double_finger_circle_to_search"
        const val LOCKSCREEN_VOICE_COMMAND = QuroModuleConfig.Keys.LOCKSCREEN_VOICE_COMMAND
        const val SCREEN_ON_VOICE_COMMAND = QuroModuleConfig.Keys.SCREEN_ON_VOICE_COMMAND
        const val AGENT_CUSTOM_MODEL = QuroModuleConfig.Keys.VENDOR_CUSTOM_MODEL
        const val AGENT_REQUIRE_PREFIX = QuroModuleConfig.Keys.VENDOR_REQUIRE_PREFIX
        const val AGENT_TERMINAL_TOOLS = "agent_terminal_tools"
        const val AGENT_BROWSER_TOOLS = "agent_browser_tools"
        const val AGENT_DEVICE_DIRECT_TOOLS = "agent_device_direct_tools"
        const val AGENT_DEVICE_SENSITIVE_READ_TOOLS = "agent_device_sensitive_read_tools"
        const val AGENT_DEVICE_SENSITIVE_ACTION_TOOLS = "agent_device_sensitive_action_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"
        const val AGENT_AUTO_COMPACTION_ENABLED = "agent_auto_compaction_enabled"
        const val AGENT_SUBAGENT_ENABLED = "agent_subagent_enabled"
        const val AGENT_VIRTUAL_DISPLAY_ENABLED = "agent_virtual_display_enabled"
        const val AGENT_RUNTIME_CONFIG_JSON = "agent_runtime_config_json"
        const val AGENT_DISABLED_TOOLS_JSON = "agent_disabled_tools_json"

        /** 未在 ZorvAI 独立建模的历史开关：给出与 Eta 一致的缺省值。 */
        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            POWER_KEY_TAKEOVER to false,
            ASSISTANT_AUTO_CONFIG to false,
            HOTWORD_SELF_HEAL to false,
            GESTURE_BAR_CIRCLE_TO_SEARCH to true,
            DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
            LOCKSCREEN_VOICE_COMMAND to false,
            SCREEN_ON_VOICE_COMMAND to false,
            AGENT_CUSTOM_MODEL to true,
            AGENT_REQUIRE_PREFIX to false,
            AGENT_TERMINAL_TOOLS to true,
            AGENT_BROWSER_TOOLS to true,
            AGENT_DEVICE_DIRECT_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
            AGENT_THINKING_ENABLED to true,
            AGENT_AUTO_COMPACTION_ENABLED to true,
            AGENT_SUBAGENT_ENABLED to true,
            AGENT_VIRTUAL_DISPLAY_ENABLED to true,
        )
    }

    /** Hook 进程缓存的只读 remote preferences，由 ModuleMain 在 onModuleLoaded 注入。 */
    @Volatile
    private var remote: SharedPreferences? = null

    @Volatile
    private var listeners = emptyList<SharedPreferences.OnSharedPreferenceChangeListener>()

    fun initLocal(context: Context) {
        // ZorvAI 的 UI 侧由 QuroModulePrefs.attachFromService 注入，无需第二份本地配置。
        runCatching {
            QuroModulePrefs.attach(
                context.getSharedPreferences(GROUP, Context.MODE_PRIVATE),
                canWrite = false,
            )
        }
    }

    fun attachRemote(prefs: SharedPreferences?) {
        remote = prefs
        QuroModulePrefs.attach(prefs, canWrite = false)
        // 补挂此前已注册的监听，避免 onModuleLoaded 时序差导致丢失配置变更。
        listeners.forEach { listener ->
            runCatching { prefs?.registerOnSharedPreferenceChangeListener(listener) }
        }
    }

    fun registerRemoteListener(listener: SharedPreferences.OnSharedPreferenceChangeListener): Boolean {
        val prefs = remote ?: return false
        listeners = listeners + listener
        return runCatching {
            prefs.registerOnSharedPreferenceChangeListener(listener)
            true
        }.getOrDefault(false)
    }

    /** 统一走 ZorvAI 自己的开关读取（含 defaultOf 兜底）。 */
    fun isEnabled(key: String): Boolean = when (key) {
        Keys.POWER_KEY_TAKEOVER -> QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.VENDOR_ASSISTANT_TAKEOVER)
        Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH -> QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.GEMINI_CIRCLE_TO_SEARCH)
        Keys.AGENT_TERMINAL_TOOLS,
        Keys.AGENT_BROWSER_TOOLS,
        Keys.AGENT_DEVICE_DIRECT_TOOLS,
        Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
        Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
        Keys.AGENT_THINKING_ENABLED,
        Keys.AGENT_AUTO_COMPACTION_ENABLED,
        Keys.AGENT_SUBAGENT_ENABLED,
        Keys.AGENT_VIRTUAL_DISPLAY_ENABLED,
        -> Keys.BOOLEAN_DEFAULTS.getValue(key)
        else -> QuroModulePrefs.isEnabled(key)
    }

    fun getString(key: String): String = runCatching {
        remote?.getString(key, null).orEmpty()
    }.getOrDefault("")

    fun powerAssistantTarget(): PowerAssistantTarget = when (QuroModulePrefs.powerTarget()) {
        QuroModuleConfig.PowerTarget.QURO -> PowerAssistantTarget.ETA
        QuroModuleConfig.PowerTarget.GEMINI -> PowerAssistantTarget.GEMINI
        else -> PowerAssistantTarget.OEM
    }
}
