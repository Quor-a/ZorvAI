package com.ai.assistance.quro.plugin.engine.bridge

import com.ai.assistance.quro.plugin.contract.ParamType
import com.ai.assistance.quro.plugin.contract.ToolArgs
import com.ai.assistance.quro.plugin.contract.ToolExecutor
import com.ai.assistance.quro.plugin.contract.ToolParamSpec
import com.ai.assistance.quro.plugin.contract.ToolResult
import com.ai.assistance.quro.plugin.contract.ToolSpec
import com.ai.assistance.quro.plugin.engine.registry.ExtensionRegistry
import com.ai.assistance.quro.plugin.extension.AciCapabilityExtension
import com.ai.assistance.quro.plugin.extension.AiToolExtension
import com.ai.assistance.quro.plugin.extension.ExtensionType

/**
 * ★ ACI 融合桥（双向）—— 本框架独立实现，不改动 ACI 本身。
 *
 * 方向一（插件 → ACI 生态）：
 *   插件通过 aciCapability{} 声明的能力，由本桥接器汇总成一份「能力清单」，
 *   交给宿主已有的 ACI 服务端通道对外暴露。
 *   对外 App / 其他 Agent 看到的仍是标准 ACI 能力（name + description + version + params），
 *   完全符合 ACI 的四成员契约，感知不到它其实由插件实现。
 *
 * 方向二（ACI 生态 → 插件 / LLM）：
 *   宿主 ACI 客户端发现到的外部受控端能力，由本桥接器反向注册成 AI_TOOL，
 *   这样 LLM 在 function calling 里能直接调用外部 App 的能力。
 *
 * 融合点只有这两个方法，不侵入 ACI 的 BaseACIService / AIDL 任何代码。
 */
object AciBridge {

    /** 宿主侧注入：把能力清单同步给 ACI 服务端（IACIService 实现） */
    var capabilitySink: ((List<AciCapabilitySpec>) -> Unit)? = null
    /** 宿主侧注入：调用 ACI 真实能力 */
    var aciInvoker: (suspend (String, Map<String, Any?>) -> String)? = null
    /** 宿主侧注入：读取 ACI 已发现的外部能力 */
    var externalCapabilities: (() -> List<AciCapabilitySpec>)? = null

    private const val ACI_PROVIDER_ID = "aci"

    // ==================== 方向一 ====================

    /**
     * 把插件声明的 ACI 能力同步给 ACI 服务端。
     * 每次插件安装/卸载/重载后调用一次即可。
     */
    fun publishPluginCapabilities() {
        val list = pluginCapabilities()
        capabilitySink?.invoke(list)
    }

    /** 当前插件声明的 ACI 能力快照 */
    fun pluginCapabilities(): List<AciCapabilitySpec> =
        ExtensionRegistry.list<AciCapabilityExtension>(ExtensionType.ACI_CAPABILITY)
            .map { ext ->
                AciCapabilitySpec(
                    name = ext.id,
                    description = ext.description,   // ★ 必须是能力说明，不能填 version
                    version = ext.version,
                    params = ext.parameters.map {
                        AciParamSpec(it.name, it.type.jsonType, it.description, it.required)
                    }
                )
            }

    /** ACI 服务端收到外部调用时转进来；未命中返回 null（调用方继续走自身能力） */
    suspend fun dispatchToPlugin(capabilityId: String, args: Map<String, Any?>): String {
        val ext = ExtensionRegistry.get(ExtensionType.ACI_CAPABILITY, capabilityId)
            as? AciCapabilityExtension ?: return "ERROR: capability not found: $capabilityId"
        return when (val r = ext.handler(ToolArgs(args))) {
            is ToolResult.Text -> r.text
            is ToolResult.Json -> r.json
            is ToolResult.Error -> "ERROR: ${r.message}"
        }
    }

    fun hasPluginCapability(capabilityId: String): Boolean =
        ExtensionRegistry.get(ExtensionType.ACI_CAPABILITY, capabilityId) != null

    // ==================== 方向二 ====================

    /**
     * 把 ACI 已发现的外部受控端能力反向注册成 AI 工具，
     * 这样 LLM 可以直接编排调用外部 App。
     * 幂等：重复调用只会按 id 覆盖，不会堆积。
     */
    fun mirrorAciCapabilitiesAsTools() {
        val caps = externalCapabilities?.invoke() ?: return
        caps.forEach { cap ->
            val spec = ToolSpec(
                name = "aci_${cap.name}",
                description = "[ACI] ${cap.description}",
                parameters = cap.params.mapNotNull { p ->
                    runCatching {
                        ToolParamSpec(
                            name = p.name,
                            type = ParamType.values().first { it.jsonType == p.type },
                            description = p.description,
                            required = p.required
                        )
                    }.getOrNull()
                }
            )
            ExtensionRegistry.register(ACI_PROVIDER_ID, AiToolExtension(
                id = "aci_${cap.name}", label = cap.description, spec = spec,
                executor = ToolExecutor { args ->
                    val out = aciInvoker?.invoke(cap.name, args.raw())
                    if (out == null) ToolResult.error("ACI 未就绪")
                    else ToolResult.text(out)
                }
            ))
        }
    }

    /** 调用外部 ACI 受控端能力 */
    suspend fun invokeExternal(capabilityId: String, args: Map<String, Any?>): ToolResult {
        val out = aciInvoker?.invoke(capabilityId, args)
            ?: return ToolResult.error("ACI 未注入")
        return ToolResult.text(out)
    }
}

/** ACI 能力描述（与 ACI Capability 字段对齐） */
data class AciCapabilitySpec(
    val name: String,
    val description: String,
    val version: String = "1.0",
    val params: List<AciParamSpec> = emptyList()
)

data class AciParamSpec(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)
