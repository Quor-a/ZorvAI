package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.plugin.QuroPluginAciRegistry
import com.ai.assistance.quro.core.plugin.QuroPluginHost
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * APK 级插件框架的宿主管理工具（给 AI 用）。
 *
 * 这组工具让 AI 能自己列插件、装插件、卸插件、热重载插件 ——
 * 装完之后插件贡献的新工具会立刻出现在 AI 的工具集里（下一轮 function calling 可见）。
 *
 * 插件工具本身不在这里实现：它们由插件 APK 里注册的 AI_TOOL 扩展动态提供，
 * 经 [QuroPluginHost.executePluginTool] 执行。
 */

/** plugin_list：列出已安装插件 + 其贡献的扩展点 */
class PluginListTool : QuroTool {
    override val name = "plugin_list"
    override val description =
        "列出已安装的 APK 级插件：插件包名、名称、版本、是否已加载、贡献了哪些扩展点（AI 工具 / ACI 能力 / 指令 / 设置等）及数量。用于查看当前设备上装了哪些插件、插件提供了什么能力。"
    override val parametersJson =
        """{"type":"object","properties":{"plugin_id":{"type":"string","description":"可选。指定后只返回该插件的详细扩展点清单"}}}"""

    override fun run(context: Context, arguments: String): String {
        if (!QuroPluginHost.isReady()) return "插件引擎未初始化"
        val filter = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
            .optString("plugin_id", "").trim()

        val records = QuroPluginHost.installed().filter {
            filter.isEmpty() || it.pluginId == filter
        }
        if (records.isEmpty()) {
            return if (filter.isEmpty()) "当前没有安装任何插件。"
            else "未安装插件：$filter"
        }
        val arr = JSONArray()
        records.forEach { r ->
            val exts = QuroPluginHost.extensionSummary(r.pluginId)
            arr.put(JSONObject().apply {
                put("plugin_id", r.pluginId)
                put("name", r.name)
                put("version_name", r.versionName)
                put("version_code", r.versionCode)
                put("entry_class", r.entryClass)
                put("loaded", QuroPluginHost.isLoaded(r.pluginId))
                put("extension_count", exts.values.sum())
                put("extensions", JSONObject(exts as Map<*, *>))
            })
        }
        return arr.toString(2)
    }
}

/** plugin_info：看某个插件注册的全部扩展点明细（含 AI 工具名） */
class PluginInfoTool : QuroTool {
    override val name = "plugin_info"
    override val description =
        "查看某个插件注册的全部扩展点明细：AI 工具名、ACI 能力名、斜杠指令、设置项等。用于确认插件到底给 AI 增加了哪些可调用能力。"
    override val parametersJson =
        """{"type":"object","properties":{"plugin_id":{"type":"string","description":"插件包名，如 com.quro.plugin.express"}},"required":["plugin_id"]}"""

    override fun run(context: Context, arguments: String): String {
        if (!QuroPluginHost.isReady()) return "插件引擎未初始化"
        val id = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
            .optString("plugin_id", "").trim()
        if (id.isEmpty()) return "缺少参数 plugin_id"

        val all = runCatching {
            com.ai.assistance.quro.plugin.engine.registry.ExtensionRegistry.allOfPlugin(id)
        }.getOrElse { emptyList() }
        if (all.isEmpty()) return "插件「$id」未注册任何扩展（或未安装/未加载）。"

        val byType = JSONObject()
        all.groupBy { it.first.name }.forEach { (type, list) ->
            byType.put(type, JSONArray(list.map { it.second.id }))
        }
        val aciCaps = QuroPluginAciRegistry.capabilities().filter {
            com.ai.assistance.quro.plugin.engine.registry.ExtensionRegistry
                .pluginIdOf(
                    com.ai.assistance.quro.plugin.extension.ExtensionType.ACI_CAPABILITY, it.name
                ) == id
        }.map { it.name }

        return JSONObject().apply {
            put("plugin_id", id)
            put("extensions", byType)
            put("ai_tool_names", JSONArray(
                com.ai.assistance.quro.plugin.engine.bridge.HostToolBridge.collectToolSpecs()
                    .filter { spec ->
                        com.ai.assistance.quro.plugin.engine.registry.ExtensionRegistry
                            .pluginIdOf(
                                com.ai.assistance.quro.plugin.extension.ExtensionType.AI_TOOL, spec.name
                            ) == id
                    }.map { it.name }
            ))
            put("aci_capability_names", JSONArray(aciCaps))
        }.toString(2)
    }
}

/** plugin_install：从本地 APK 文件安装插件 */
class PluginInstallTool : QuroTool {
    override val name = "plugin_install"
    override val description =
        "从设备上的 APK 文件安装 APK 级插件。插件必须是 ZorvAI 插件规范打包的独立 APK（清单里声明了 quro.plugin.entry），且与宿主使用同一签名。安装成功后插件贡献的 AI 工具会立即出现在你的工具集里。"
    override val parametersJson =
        """{"type":"object","properties":{"path":{"type":"string","description":"插件 APK 的绝对路径，如 /storage/emulated/0/Download/xxx-plugin.apk"},"skip_signature_check":{"type":"boolean","description":"可选。true 表示跳过同签名校验（仅用于调试，默认 false）"}},"required":["path"]}"""

    override fun run(context: Context, arguments: String): String {
        if (!QuroPluginHost.isReady()) return "插件引擎未初始化"
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val path = jo.optString("path", "").trim()
        if (path.isEmpty()) return "缺少参数 path"
        val skip = jo.optBoolean("skip_signature_check", false)

        val apk = File(path)
        if (!apk.exists()) return "文件不存在：$path"

        val r = QuroPluginHost.install(apk, requireSameSignature = !skip)
        return if (r.success) {
            val exts = QuroPluginHost.extensionSummary(r.pluginId)
            "插件安装成功：${r.pluginId}\n贡献扩展点：${exts.entries.joinToString { "${it.key}×${it.value}" }.ifBlank { "无" }}\n新增 AI 工具：${
                com.ai.assistance.quro.plugin.engine.registry.ExtensionRegistry
                    .listByPlugin<com.ai.assistance.quro.plugin.extension.AiToolExtension>(
                        r.pluginId,
                        com.ai.assistance.quro.plugin.extension.ExtensionType.AI_TOOL
                    ).joinToString { it.id }.ifBlank { "无" }
            }"
        } else {
            "插件安装失败：${r.message}"
        }
    }
}

/** plugin_uninstall：卸载插件 */
class PluginUninstallTool : QuroTool {
    override val name = "plugin_uninstall"
    override val description = "卸载一个已安装的 APK 级插件，同时移除它贡献的所有能力（AI 工具 / ACI 能力 / 指令等）。"
    override val parametersJson =
        """{"type":"object","properties":{"plugin_id":{"type":"string","description":"插件包名"}},"required":["plugin_id"]}"""

    override fun run(context: Context, arguments: String): String {
        if (!QuroPluginHost.isReady()) return "插件引擎未初始化"
        val id = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
            .optString("plugin_id", "").trim()
        if (id.isEmpty()) return "缺少参数 plugin_id"
        return if (QuroPluginHost.uninstall(id)) "插件已卸载：$id" else "未找到插件：$id"
    }
}

/** plugin_reload：热重载插件（改完插件重装后无需重启宿主） */
class PluginReloadTool : QuroTool {
    override val name = "plugin_reload"
    override val description =
        "热重载指定插件或全部插件：重新实例化插件入口并重新注册扩展点。不传 plugin_id 则重载全部。改完插件重装后用它生效，无需重启应用。"
    override val parametersJson =
        """{"type":"object","properties":{"plugin_id":{"type":"string","description":"可选。不传则重载全部已装插件"}}}"""

    override fun run(context: Context, arguments: String): String {
        if (!QuroPluginHost.isReady()) return "插件引擎未初始化"
        val id = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
            .optString("plugin_id", "").trim()

        if (id.isNotEmpty()) {
            return if (QuroPluginHost.reload(id)) "插件已重载：$id" else "重载失败：$id（未安装或入口类加载异常）"
        }
        val results = QuroPluginHost.installed().map { rec ->
            "${rec.pluginId}: ${if (QuroPluginHost.reload(rec.pluginId)) "OK" else "失败"}"
        }
        return if (results.isEmpty()) "没有已安装的插件。" else "重载结果：\n" + results.joinToString("\n")
    }
}

/** plugin_surface_open：打开插件自带的界面（不给 surface_id 则列出全部可用界面） */
class PluginSurfaceOpenTool : QuroTool {
    override val name = "plugin_surface_open"
    override val description =
        "打开插件自带的界面（如浏览器窗口）。不传 surface_id 时列出当前所有可用界面及其 id。用户说「打开插件的某个界面/窗口」时用它。"
    override val parametersJson =
        """{"type":"object","properties":{"surface_id":{"type":"string","description":"可选。界面 id，不传则只列出可用界面"}}}"""

    override fun run(context: Context, arguments: String): String {
        if (!QuroPluginHost.isReady()) return "插件引擎未初始化"
        val id = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
            .optString("surface_id", "").trim()

        val surfaces = com.ai.assistance.quro.plugin.engine.bridge.HostToolBridge.uiSurfaces()
        if (id.isEmpty()) {
            if (surfaces.isEmpty()) return "当前没有插件提供界面。"
            return "可用插件界面（${surfaces.size}）：\n" + surfaces.joinToString("\n") {
                val pid = com.ai.assistance.quro.plugin.engine.bridge.HostToolBridge.pluginIdOfSurface(it.id) ?: "?"
                "- ${it.id} | ${it.label} | 来自 $pid"
            }
        }
        val ext = com.ai.assistance.quro.plugin.engine.bridge.HostToolBridge.surface(id)
            ?: return "没有找到插件界面：$id"

        return try {
            val it = com.ai.assistance.quro.ui.PluginSurfaceActivity.intent(context, id, ext.title)
            context.startActivity(it)
            "已打开插件界面：${ext.label}（$id）"
        } catch (t: Throwable) {
            "打开失败：${t.message ?: t.javaClass.simpleName}"
        }
    }
}

/** 全部插件管理工具（供注册表批量注册） */
val allPluginManagementTools: List<QuroTool> = listOf(
    PluginListTool(),
    PluginInfoTool(),
    PluginInstallTool(),
    PluginUninstallTool(),
    PluginReloadTool(),
    PluginSurfaceOpenTool(),
)

/** 插件管理工具名（并入 coreSpecs 默认下发集，保证 AI 开箱可见） */
val pluginManagementToolNames: Set<String> =
    allPluginManagementTools.map { it.name }.toSet()

/** 插件管理工具的规格（含已装插件动态贡献的工具） */
fun pluginHostToolSpecs(): List<com.ai.assistance.quro.core.QuroToolSpec> =
    allPluginManagementTools.map {
        com.ai.assistance.quro.core.QuroToolSpec(it.name, it.description, it.parametersJson)
    } + QuroPluginHost.toolSpecs()
