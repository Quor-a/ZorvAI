package com.ai.assistance.quro.core.plugin

import android.content.Context
import android.os.Bundle
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.aidlaci.QuroAidlAciManager
import com.ai.assistance.quro.plugin.contract.ToolParamSpec
import com.ai.assistance.quro.plugin.engine.bridge.AciBridge
import com.ai.assistance.quro.plugin.engine.bridge.AciCapabilitySpec
import com.ai.assistance.quro.plugin.engine.bridge.AciParamSpec
import com.ai.assistance.quro.plugin.engine.bridge.HostToolBridge
import com.ai.assistance.quro.plugin.engine.core.QuroPluginEngine
import com.ai.assistance.quro.plugin.engine.install.InstallResult
import com.ai.assistance.quro.plugin.engine.install.PluginRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ★ 宿主接入插件框架的唯一入口（一次性改动，之后加插件不再改宿主代码）。
 *
 * 职责：
 *  1. 启动引擎、加载已装插件；
 *  2. 把插件贡献的 AI 工具转成宿主 [QuroToolSpec]（LLM 可见 / 可调用）；
 *  3. 把插件的 suspend 执行体桥接进宿主同步工具执行链；
 *  4. 注入 ACI 双向桥（插件能力 → ACI 服务端；ACI 外部能力 → AI 工具）。
 *
 * 在 [com.ai.assistance.quro.activity.QuroApplication.onCreate] 里调用 attach()。
 */
object QuroPluginHost {

    /** 宿主自身对外声明具备的能力（插件据此做可选依赖判断，如 ctx.hasHostCapability("llm")） */
    private val HOST_CAPS = setOf(
        "llm", "memory", "tts", "stt", "aci", "terminal", "file", "web", "screen", "shell"
    )

    @Volatile
    private var attachContext: Context? = null

    /** 已注入的 ACI 外部能力快照（由 AciBridge 能力发现回调刷新） */
    @Volatile
    private var aciExternalCaps: List<AciCapabilitySpec> = emptyList()

    // ==================== 生命周期 ====================

    fun attach(context: Context) {
        val app = context.applicationContext
        attachContext = app
        QuroPluginEngine.init(app, HOST_CAPS)

        // ---- ACI 桥注入：方向一（插件能力 → ACI 服务端清单） ----
        AciBridge.capabilitySink = { caps ->
            QuroPluginAciRegistry.publish(caps)
            android.util.Log.i(TAG, "已同步 ${caps.size} 个插件 ACI 能力")
        }

        // ---- ACI 桥注入：方向二（ACI 外部能力 → AI 工具） ----
        AciBridge.externalCapabilities = { aciExternalCaps }
        AciBridge.aciInvoker = { name, args -> invokeAci(name, args) }

        // ---- 加载所有已装插件（插件 Entry.onCreate 里注册扩展） ----
        QuroPluginEngine.loadAllInstalled()

        // ---- 同步能力清单 + 反向镜像 ACI 能力为 AI 工具 ----
        refreshAciMirror()
        QuroPluginAciRegistry.publish(AciBridge.pluginCapabilities())
    }

    /** 重新读取 ACI 已发现的外部能力，并镜像成 AI 工具（ACI 发现完成/变化后调用） */
    fun refreshAciMirror() {
        aciExternalCaps = runCatching { collectAciCapabilities() }.getOrElse { emptyList() }
        AciBridge.mirrorAciCapabilitiesAsTools()
    }

    fun isReady(): Boolean = QuroPluginEngine.isReady()

    // ==================== AI 工具接入 ====================

    /**
     * 插件贡献的全部 AI 工具规格（转成宿主 QuroToolSpec 下发给 LLM）。
     * 宿主 [com.ai.assistance.quro.core.tools.QuroToolRegistry.coreSpecs] 会并入此列表。
     */
    fun toolSpecs(): List<QuroToolSpec> =
        runCatching {
            HostToolBridge.collectToolSpecs().map {
                QuroToolSpec(it.name, it.description, schemaOf(it.parameters))
            }
        }.getOrElse { emptyList() }

    fun hasPluginTool(name: String): Boolean =
        runCatching { HostToolBridge.hasTool(name) }.getOrDefault(false)

    /**
     * 执行插件工具。插件执行体是 suspend 的，宿主工具链在此桥接。
     * 未命中返回 null（调用方继续走内置工具）。
     */
    suspend fun executePluginTool(name: String, args: Map<String, Any?>): String? {
        if (!hasPluginTool(name)) return null
        return runCatching { HostToolBridge.executeTool(name, args) }
            .getOrElse { "插件工具执行异常: ${it.message}" }
    }

    /** ToolParamSpec 列表 → OpenAI function-calling 参数 JSON Schema */
    private fun schemaOf(params: List<ToolParamSpec>): String {
        val props = JSONObject()
        val required = JSONArray()
        params.forEach { p ->
            val o = JSONObject()
            o.put("type", p.type.jsonType)
            o.put("description", p.description)
            if (p.enumValues.isNotEmpty()) o.put("enum", JSONArray(p.enumValues))
            p.defaultValue?.let { d -> o.put("default", d) }
            props.put(p.name, o)
            if (p.required) required.put(p.name)
        }
        return JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.length() > 0) put("required", required)
        }.toString()
    }

    // ==================== 安装 / 卸载 / 重载 ====================

    fun install(apk: File, requireSameSignature: Boolean = true): InstallResult =
        QuroPluginEngine.install(apk, requireSameSignature)

    /** 宿主 APK 当前签名的第一张证书 SHA-256 指纹（调试用） */
    fun hostFingerprint(): String? = QuroPluginEngine.hostFingerprint()

    fun uninstall(pluginId: String): Boolean = QuroPluginEngine.uninstall(pluginId)

    fun reload(pluginId: String): Boolean =
        QuroPluginEngine.reload(pluginId).also { if (it) QuroPluginAciRegistry.publish(AciBridge.pluginCapabilities()) }

    fun installed(): List<PluginRecord> = QuroPluginEngine.installed()
    fun loadedIds(): List<String> = QuroPluginEngine.loadedIds()
    fun isLoaded(pluginId: String): Boolean = QuroPluginEngine.isLoaded(pluginId)

    /** 某插件注册的扩展摘要（类型 → 数量），供插件面板展示 */
    fun extensionSummary(pluginId: String): Map<String, Int> =
        runCatching {
            com.ai.assistance.quro.plugin.engine.registry.ExtensionRegistry
                .allOfPlugin(pluginId)
                .groupingBy { it.first.name }
                .eachCount()
        }.getOrDefault(emptyMap())

    // ==================== ACI 双向 ====================

    /** 收集 ACI 已发现的外部受控端能力（供镜像成 AI 工具） */
    private fun collectAciCapabilities(): List<AciCapabilitySpec> {
        val mgr = runCatching { QuroAidlAciManager.getInstance() }.getOrNull() ?: return emptyList()
        return mgr.getCapabilityIndex().values.flatten().map { cap ->
            AciCapabilitySpec(
                name = cap.id,
                description = cap.description ?: cap.id,
                version = cap.version ?: "1.0",
                params = cap.params.map { p ->
                    AciParamSpec(
                        name = p.name,
                        type = p.type ?: "string",
                        description = p.description ?: "",
                        required = p.required
                    )
                }
            )
        }
    }

    /** 调用外部 ACI 受控端能力（按能力 id 反查提供方包名） */
    private suspend fun invokeAci(capabilityId: String, args: Map<String, Any?>): String =
        withContext(Dispatchers.IO) {
            val mgr = runCatching { QuroAidlAciManager.getInstance() }.getOrNull()
                ?: return@withContext "ERROR: ACI 未初始化"
            val pkg = mgr.getCapabilityIndex().entries
                .firstOrNull { e -> e.value.any { it.id == capabilityId } }?.key
                ?: return@withContext "ERROR: 未找到提供能力「$capabilityId」的受控端"
            val bundle = Bundle().apply {
                args.forEach { (k, v) ->
                    when (v) {
                        null -> putString(k, "")
                        is String -> putString(k, v)
                        is Int -> putInt(k, v)
                        is Long -> putLong(k, v)
                        is Boolean -> putBoolean(k, v)
                        is Double -> putDouble(k, v)
                        is Float -> putDouble(k, v.toDouble())
                        else -> putString(k, v.toString())
                    }
                }
            }
            runCatching {
                val resp = mgr.call(pkg, capabilityId, bundle)
                if (resp.isSuccess) {
                    resp.result?.let { b ->
                        b.getString("result") ?: b.getString("data") ?: b.toString()
                    } ?: "OK"
                } else {
                    "ERROR: ACI 调用失败（$pkg/$capabilityId）"
                }
            }.getOrElse { "ERROR: ${it.message}" }
        }

    private const val TAG = "QuroPluginHost"
}

/**
 * 插件 ACI 能力的宿主侧收纳槽。
 *
 * 插件能力并入宿主 ACI 服务端的对外清单；外部调用先查本槽，未命中再落回宿主自身能力。
 * 之所以单独放一个对象：ACI 服务端（[com.ai.assistance.quro.service.QuroTerminalAciService]）
 * 在另一处代码里读它，避免插件框架与 ACI 服务互相直接依赖。
 */
object QuroPluginAciRegistry {

    @Volatile
    private var caps: List<AciCapabilitySpec> = emptyList()

    fun publish(list: List<AciCapabilitySpec>) { caps = list }

    fun capabilities(): List<AciCapabilitySpec> = caps

    /** 该能力是否由插件提供 */
    fun isPluginCapability(id: String): Boolean = caps.any { it.name == id }

    /** 外部分发入口：命中插件能力则执行，返回 null 表示未命中 */
    suspend fun dispatch(id: String, params: Map<String, Any?>): String? {
        if (!isPluginCapability(id)) return null
        return runCatching { AciBridge.dispatchToPlugin(id, params) }.getOrNull()
    }
}
