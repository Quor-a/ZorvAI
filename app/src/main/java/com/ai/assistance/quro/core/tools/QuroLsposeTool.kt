package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.R
import com.ai.assistance.quro.core.assistant.QuroAgentBridgeInbox
import com.ai.assistance.quro.core.assistant.QuroAgentBridgeProtocol
import com.ai.assistance.quro.core.assistant.QuroAssistantCapabilities
import com.ai.assistance.quro.core.privilege.QuroLSPosed
import com.ai.assistance.quro.core.xposed.QuroModuleConfig
import com.ai.assistance.quro.core.xposed.QuroModulePrefs
import com.ai.assistance.quro.core.xposed.QuroModuleServiceState
import com.ai.assistance.quro.util.qstr
import org.json.JSONArray
import org.json.JSONObject

/**
 * LSPosed 模块 AI 直驱工具。
 *
 * 旧版（API 82）的跨应用注入 / 系统重定向桥、外部存储 lsposed_bridge.json 已整体退役；
 * 现在这个工具直接操作框架服务：查框架通信状态、查真实作用域与已加载模块进程、
 * 读写 RemotePreferences 里的能力开关、请求补齐作用域、设置长按电源键目标。
 *
 * 工具名 `lsposed` 与注册表 / RAG 索引保持不变（历史会话与工具分类不因模块重写而断裂）。
 */
class QuroLsposeTool : QuroTool {
    override val name = "lsposed"

    override val description =
        qstr(R.string.qk_03609) +
            "status=框架通信/作用域/已加载模块进程/全部能力开关；" +
            "scope=逐项列出声明作用域是否配置、是否有进程确实加载了 Hook；" +
            "enable|disable=开关某个 capability（见 status 的 capabilities 列表），" +
            "需框架服务已连接，未连接时开关不可修改；" +
            "set_power_target=设置长按电源键目标（vendor/gemini/quro）；" +
            "request_scope=请求框架补齐缺失作用域（会弹框架授权）；" +
            "capabilities=列出接管入口可使用的 Zorv AI 全能力（运行时配置、工具能力域、Skills/MCP/记忆数量、" +
            "Root/ADB/LSPosed/Shizuku 四通道、厂商助手能力映射）；" +
            "inbox=最近若干次厂商助手接管的请求/回复/成败/耗时（可观测，不写主聊天库）。" +
            "参数 {\"action\":\"status|scope|enable|disable|set_power_target|request_scope|capabilities|inbox\"," +
            "\"capability\":\"hook_vendor_assistant_takeover\",\"target\":\"quro\",\"limit\":10}"

    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"status / scope / enable / disable / set_power_target / request_scope / capabilities / inbox"},
            "capability":{"type":"string","description":"enable|disable 必填，能力键名取自 status 返回的 capabilities"},
            "target":{"type":"string","description":"set_power_target 必填：vendor=厂商助手 / gemini=Gemini / quro=Zorv AI"},
            "limit":{"type":"integer","description":"inbox 可选，返回条数，默认 10，上限 30"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON：$arguments" }
        return when (val action = jo.optString("action", "").trim().lowercase()) {
            "status" -> status(context)
            "scope" -> scope()
            "enable", "disable" -> setSwitch(jo, enabled = action == "enable")
            "set_power_target" -> setPowerTarget(jo)
            "request_scope" -> requestScope()
            "capabilities" -> capabilities(context)
            "inbox" -> inbox(context, jo.optInt("limit", 10))
            "foreground" ->
                "旧版跨应用注入桥已随 API 82 模块退役；当前前台 App 请改用 screen(action=foreground) 或 ui_tree。"
            else -> "未知 action: $action（支持 status / scope / enable / disable / set_power_target / request_scope / capabilities）"
        }
    }

    /**
     * 摘要版能力清单（不把 200+ 工具的完整 JSON 塞进对话上下文）。
     * 完整清单由 Hook 进程经 [QuroAgentBridgeProtocol.METHOD_CAPABILITIES] 直接读取。
     */
    private fun capabilities(ctx: Context): String = JSONObject().apply {
        val full = runCatching { QuroAssistantCapabilities.snapshot(ctx) }.getOrElse {
            put("ok", false)
            put("error", "能力清单读取失败：${it.javaClass.simpleName}")
            return@apply
        }
        put("ok", true)
        put("bridge_authority", QuroAgentBridgeProtocol.AUTHORITY)
        put("runtime", full.optJSONObject("runtime"))
        put("channels", full.optJSONArray("channels"))
        put("capability_domains", full.optJSONArray("capability_domains"))
        put("entry_sources", full.optJSONArray("entry_sources"))
        put("tool_count", full.optJSONArray("tools")?.length() ?: 0)
        put("skill_count", full.optJSONArray("skills")?.length() ?: 0)
        put("mcp_server_count", full.optJSONArray("mcp_servers")?.length() ?: 0)
        put("note", "完整工具/技能/MCP 清单由接管入口经 content://${QuroAgentBridgeProtocol.AUTHORITY} 的 " +
            "${QuroAgentBridgeProtocol.METHOD_CAPABILITIES} 方法读取；" +
            "接管后执行的是 Zorv AI 完整 Agent 运行时，不是裸模型转发。")
    }.toString(2)

    /**
     * 最近若干次厂商助手接管记录。
     *
     * 这是「接管是否真的跑在完整运行时上」的直接证据：入口、原始请求、回复、成败、耗时都在这里，
     * 不需要连 logcat 也能判断某次电源键 / 小布请求到底是走了 Zorv AI 还是回落到了厂商原生。
     */
    private fun inbox(ctx: Context, limit: Int): String = JSONObject().apply {
        val records = QuroAgentBridgeInbox.recent(ctx, limit)
        put("ok", true)
        put("count", records.size)
        put("max", QuroAgentBridgeInbox.MAX)
        put("records", JSONArray().apply { records.forEach { put(it.toJson()) } })
        put("note", "按时间倒序；只保留最近 ${QuroAgentBridgeInbox.MAX} 条，存于应用私有存储，不写主聊天库。")
    }.toString(2)

    private fun status(ctx: Context): String = JSONObject().apply {
        put("module_api", 102)
        put("framework_installed", QuroLSPosed.isInstalled(ctx))
        put("framework_connected", QuroModuleServiceState.isConnected())
        put("framework_family", QuroLSPosed.frameworkFamily(ctx).name)
        put("framework_label", QuroLSPosed.frameworkLabel(ctx))
        put("api102_supported", QuroLSPosed.supportsApi102(ctx))
        put("compatibility", QuroLSPosed.compatibilityNote(ctx))
        put("zygisk_provider", QuroLSPosed.zygiskProvider().label)
        put("managers", JSONArray().apply {
            QuroLSPosed.managerDefs(ctx).forEach { def ->
                put(JSONObject().apply {
                    put("package", def.pkg)
                    put("label", def.label)
                    put("api102", def.api102)
                    put("dial_code", def.dialCode ?: JSONObject.NULL)
                })
            }
        })
        val st = QuroModuleServiceState.status()
        put("framework_name", st.frameworkName ?: JSONObject.NULL)
        put("framework_version", st.frameworkVersion ?: JSONObject.NULL)
        put("api_version", st.apiVersion)
        put("scope", JSONArray(st.scope))
        put("missing_scope", JSONArray(st.missingScope))
        put("running_targets", JSONArray().apply {
            st.runningTargets.forEach { t ->
                put(JSONObject().apply {
                    put("process", t.processName)
                    put("state", t.state)
                    put("pid", t.pid)
                })
            }
        })
        put("remote_prefs_writable", QuroModulePrefs.canWrite())
        put("capabilities", JSONArray().apply {
            QuroModuleConfig.ALL_SWITCHES.forEach { def ->
                put(JSONObject().apply {
                    put("key", def.key)
                    put("title", def.title)
                    put("enabled", QuroModulePrefs.isEnabled(def.key))
                    put("scope", JSONArray(def.scope))
                })
            }
        })
        put("power_assistant_target", QuroModulePrefs.powerTarget())
        put("summary", QuroLSPosed.statusText(ctx))
    }.toString(2)

    private fun scope(): String {
        val st = QuroModuleServiceState.status()
        if (!st.connected) return "框架服务未连接：无法读取真实作用域。请在 LSPosed 管理器启用本模块并重启目标进程。"
        return JSONObject().apply {
            put("declared", JSONArray(QuroModuleConfig.DECLARED_SCOPE))
            put("configured", JSONArray(st.scope))
            put("missing", JSONArray(st.missingScope))
            put("targets", JSONArray().apply {
                QuroModuleConfig.DECLARED_SCOPE.forEach { pkg ->
                    put(JSONObject().apply {
                        put("package", pkg)
                        put("label", QuroModuleConfig.scopeLabel(pkg))
                        put("configured", pkg in st.scope)
                        put("hook_loaded", st.isScopeActive(pkg))
                    })
                }
            })
            put("note", "服务连接不代表模块已启用；hook_loaded 才表示目标进程确实加载了模块代码。")
        }.toString(2)
    }

    private fun setSwitch(jo: JSONObject, enabled: Boolean): String {
        val key = jo.optString("capability", "").trim()
        if (key.isEmpty()) return "缺少 capability：请先用 status 查可用键名。"
        val def = QuroModuleConfig.switchOf(key)
            ?: return "未知 capability：$key。可用键：${
                QuroModuleConfig.ALL_SWITCHES.joinToString { it.key }
            }"
        if (!QuroModuleServiceState.isConnected()) {
            return "框架服务未连接，开关不可修改。请在 LSPosed 管理器启用本模块后重试。"
        }
        return if (QuroModulePrefs.setEnabled(key, enabled)) {
            val status = QuroModuleServiceState.status()
            val missing = status.missingScope.intersect(def.scope.toSet())
            val tail = if (missing.isEmpty()) "" else
                "\n⚠️ 该能力所需作用域尚未配置：${missing.joinToString()}，可用 action=request_scope 补齐。"
            "✅ ${def.title} 已${if (enabled) "开启" else "关闭"}（$key）。$tail"
        } else {
            "❌ 写入 RemotePreferences 失败：框架服务可能已断开或只读。"
        }
    }

    private fun setPowerTarget(jo: JSONObject): String {
        val target = jo.optString("target", "").trim()
        if (target !in QuroModuleConfig.PowerTarget.ALL) {
            return "target 必须是 ${QuroModuleConfig.PowerTarget.ALL.joinToString(" / ")}"
        }
        return if (QuroModulePrefs.setPowerTarget(target)) {
            "✅ 长按电源键目标已设为 $target"
        } else {
            "❌ 写入失败：框架服务未连接或值非法。"
        }
    }

    private fun requestScope(): String {
        val missing = QuroModuleServiceState.status().missingScope
        if (missing.isEmpty()) return "作用域已完整，无需补齐。"
        if (!QuroModuleServiceState.isConnected()) return "框架服务未连接，无法请求作用域。"
        // 框架授权是异步回调；这里只发起请求，结果由 UI / 框架日志呈现。
        QuroModuleServiceState.requestMissingScope { _, _ -> }
        return "已向框架发起作用域补齐请求：${missing.joinToString()}。请在 LSPosed 弹窗中确认，随后重启目标进程。"
    }
}
