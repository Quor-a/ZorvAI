package com.ai.assistance.quro.genui.app.agent.tools

import android.content.Context
import com.ai.assistance.quro.core.QuroToolCall
import com.ai.assistance.quro.core.QuroToolResult
import com.ai.assistance.quro.core.tools.QuroToolEngine
import com.ai.assistance.quro.core.tools.QuroToolRegistry
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import com.ai.assistance.quro.genui.app.agent.AgentMemory
import com.ai.assistance.quro.genui.app.agent.ToolGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * GenUI 全模式（GenScaffold / AgentLoop）的工具适配层 —— 把工具调用**整体对接到 ZorvAI 的完整工具系统**。
 *
 * 此前 GenUI 全模式用自己的 [BuiltinTools]（仅 ~30 个 GenUI 私有的小工具集，且各自重实现了一遍
 * web/记忆/文件等能力，与 ZorvAI 主对话的工具是两套互不相通的孤岛）。现改为：
 *  - 工具声明（declarations）：取自 ZorvAI 的 [QuroToolRegistry.coreSpecs]（即主对话默认下发的
 *    "完整工具集"——终端 / ACI / MCP / CMS / Linux / 屏幕控制 / 文件 / 记忆 / 经验 / 动态 UI 等全部在内）；
 *  - 工具执行（execute）：经 ZorvAI 的 [QuroToolEngine] 派发，复用主对话同一套真实工具实现；
 *  - 权限：交由 [QuroToolEngine] 内部自带的权限处理（QuroPermissionHolder，与主对话一致），
 *    不再走 GenUI 自己的 ToolGate 门禁——GenUI 的 L1–L5 策略层保留给「GenUI 私有设置页」语义，
 *    但工具真正执行由 ZorvAI 统一把关。
 *  - 记忆库（memory）：仍用已对接好的 [AgentMemory]（→ QuroMemoryRepository，group="genui"），
 *    与 ZorvAI 主对话共用同一份记忆。
 *
 * 对外暴露与 [BuiltinTools] 完全同形的成员（declarations / execute / gateFor / gate / memory），
 * 因此 [com.ai.assistance.quro.genui.app.agent.AgentLoop] 只需把 `BuiltinTools` 换成 `ZorvToolAdapter`，
 * 其余决策轮 / 渲染轮逻辑零改动。
 */
class ZorvToolAdapter(context: Context) {

    private val appContext = context.applicationContext

    /** ZorvAI 完整工具注册表（含全部内置工具 + 导入工具 + 技能工具）。 */
    private val registry: QuroToolRegistry = buildQuroRegistry(appContext).apply {
        attach(appContext)
    }

    /** ZorvAI 双引擎工具执行器（droid-mcp 派发 + 权限前置申请）。 */
    private val engine = QuroToolEngine(registry).apply {
        setContext(appContext)
    }

    /** 记忆库（已委托 QuroMemoryRepository）。 */
    val memory = AgentMemory(appContext)

    /**
     * GenUI 权限策略存储（与 GenUI「权限设置」屏共用同一份 QuroAI 共享 SharedPreferences）。
     * 对接 ZorvAI 后，工具是否真正能执行由 [QuroToolEngine] 内部的 QuroPermissionHolder 把关，
     * 这里的 ToolGate 仅作「GenUI 权限设置屏」的数据落点——"永久允许"把偏好记下来，保持设置屏一致。
     */
    private val policyStore = ToolGate(appContext)

    /** 对应 GenUI 权限卡上的"永久允许"：把工具策略固化为 ALWAYS_ALLOW 落到 GenUI 权限存储。 */
    fun grantAlways(tool: String) = policyStore.grantAlways(tool)

    /**
     * 权限门禁：对接 ZorvAI 后，真正的权限把关在 [QuroToolEngine] 内部（QuroPermissionHolder），
     * 这里对 GenUI 决策轮返回「一律放行」，避免双重弹窗；同时保留 [ToolGate.level] 供时间线显示等级。
     *
     * 注意：必须是**具名类**（[ZorvGate]），不能用匿名 object——AgentLoop 跨文件访问
     * gate.modeOf / gate.authorize 时，匿名对象含 suspend 成员的签名无法解析（Unresolved reference）。
     */
    val gate = ZorvGate()

    /** OpenAI function-calling 的工具声明：来自 ZorvAI 的 coreSpecs（完整工具集）。 */
    fun declarations(): JSONArray {
        val specs = registry.coreSpecs()
        val arr = JSONArray()
        for (spec in specs) {
            val params = runCatching { JSONObject(spec.parametersJson) }
                .getOrDefault(JSONObject("""{"type":"object","properties":{}}"""))
            arr.put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", spec.name)
                    .put("description", spec.description)
                    .put("parameters", params)))
        }
        return arr
    }

    /**
     * 执行单个工具：把 GenUI 的 (name, JSONObject) 调用转成 ZorvAI 的 [QuroToolCall]，
     * 经 [QuroToolEngine] 派发，再把结果规整成 GenUI AgentLoop 期望的 [JSONObject]。
     *
     * 结果规整策略：
     *  - 若 ZorvAI 返回的是合法 JSON 对象（如 web_search 的 {"results":[...]}），原样回传；
     *  - 若为纯文本，带失败特征（失败/错误/异常/超时/需要权限/未知工具）则包成 {"error":"..."}，
     *    否则包成 {"result":"..."}，使 AgentLoop 的摘要/事件逻辑正常工作。
     */
    suspend fun execute(name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val call = QuroToolCall(name = name, arguments = args.toString())
        val results: List<QuroToolResult> = runCatching {
            engine.execute(appContext, listOf(call))
        }.getOrDefault(listOf(QuroToolResult(name, "工具执行异常：引擎未返回结果")))
        val raw = results.firstOrNull()?.result ?: "{}"
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
        if (parsed != null) return@withContext parsed
        val o = JSONObject()
        if (looksFailed(raw)) o.put("error", raw) else o.put("result", raw)
        o
    }

    /** GenUI 决策轮按"工具族"查询权限等级；ZorvAI 工具名不在 GenUI 旧表里，直接返回原名（等级回退到 L5 显示）。 */
    fun gateFor(name: String): String = name

    private fun looksFailed(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("失败") || t.contains("错误") || t.contains("异常") ||
                t.contains("超时") || t.contains("需要权限") || t.contains("未授权") ||
                t.contains("未知工具") || t.contains("error") || t.contains("exception")
    }
}

/**
 * GenUI 全模式的权限门禁适配层（具名类，供 [AgentLoop] 跨文件稳定解析）。
 *
 * 对接 ZorvAI 后的设计：真正的权限把关已经下沉到 [QuroToolEngine] 内部
 * （QuroPermissionHolder，与 ZorvAI 主对话完全一致），因此这里对 GenUI 决策轮
 * 一律返回「放行」（modeOf = ALWAYS_ALLOW，authorize = null），避免 GenUI 与 ZorvAI
 * 两套权限流程叠加导致重复弹窗 / 双重拦批。
 *
 * 时间线里仍用 [ToolGate.level] 取等级做展示（如授权卡等级徽标），不影响放行决策。
 */
class ZorvGate {
    fun modeOf(tool: String): ToolGate.AuthMode = ToolGate.AuthMode.ALWAYS_ALLOW

    suspend fun authorize(
        tool: String,
        brief: String,
        ask: suspend (tool: String, brief: String, level: Int) -> Boolean
    ): String? = null
}
