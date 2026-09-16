package com.ai.assistance.quro.plugin.engine.bridge

import com.ai.assistance.quro.plugin.contract.ToolArgs
import com.ai.assistance.quro.plugin.contract.ToolExecutor
import com.ai.assistance.quro.plugin.contract.ToolParamSpec
import com.ai.assistance.quro.plugin.contract.ToolResult
import com.ai.assistance.quro.plugin.engine.registry.ExtensionRegistry
import com.ai.assistance.quro.plugin.extension.AiToolExtension
import com.ai.assistance.quro.plugin.extension.ChatCardExtension
import com.ai.assistance.quro.plugin.extension.CodeRuntimeExtension
import com.ai.assistance.quro.plugin.extension.CommandExtension
import com.ai.assistance.quro.plugin.extension.ExtensionType
import com.ai.assistance.quro.plugin.extension.FileHandlerExtension
import com.ai.assistance.quro.plugin.extension.ModelProviderExtension
import com.ai.assistance.quro.plugin.extension.RagSourceExtension
import com.ai.assistance.quro.plugin.extension.ScheduleTaskExtension
import com.ai.assistance.quro.plugin.extension.SettingExtension
import com.ai.assistance.quro.plugin.extension.UiSurfaceExtension

/**
 * 宿主侧取用桥：把插件注册的扩展，转换成宿主各功能模块实际消费的形态。
 *
 * ★ 宿主里唯一需要改动的地方就是调用这几个方法（一次性，之后加插件不用再改）。
 */
object HostToolBridge {

    /** 供 ToolRegistry 调用：拉取所有插件 AI 工具，转成宿主可注册的描述 */
    fun collectToolSpecs(): List<ToolDescriptor> =
        ExtensionRegistry.list<AiToolExtension>(ExtensionType.AI_TOOL).map {
            ToolDescriptor(it.spec.name, it.spec.description, it.spec.parameters, it.executor)
        }

    data class ToolDescriptor(
        val name: String,
        val description: String,
        val parameters: List<ToolParamSpec>,
        val executor: ToolExecutor
    )

    /** 执行某个插件工具（宿主 executeTool 转发到这里） */
    suspend fun executeTool(name: String, args: Map<String, Any?>): String {
        val ext = ExtensionRegistry.get(ExtensionType.AI_TOOL, name) as? AiToolExtension
            ?: return "ERROR: 未找到工具 $name"
        return when (val r = ext.executor.execute(ToolArgs(args))) {
            is ToolResult.Text -> r.text
            is ToolResult.Json -> r.json
            is ToolResult.Error -> "ERROR: ${r.message}"
        }
    }

    fun hasTool(name: String): Boolean =
        ExtensionRegistry.get(ExtensionType.AI_TOOL, name) != null

    fun commands(): List<CommandExtension> = ExtensionRegistry.list(ExtensionType.COMMAND)
    fun settings(): List<SettingExtension> = ExtensionRegistry.list(ExtensionType.SETTING)
    fun cards(): List<ChatCardExtension> = ExtensionRegistry.list(ExtensionType.CHAT_CARD)

    /** 插件自带的界面表面（宿主用通用承载 Activity 打开） */
    fun uiSurfaces(): List<UiSurfaceExtension> =
        ExtensionRegistry.list(ExtensionType.UI_SURFACE)

    fun surface(id: String): UiSurfaceExtension? =
        ExtensionRegistry.get(ExtensionType.UI_SURFACE, id) as? UiSurfaceExtension

    /** 某界面表面属于哪个插件（承载 Activity 里要回填给插件的身份） */
    fun pluginIdOfSurface(id: String): String? =
        ExtensionRegistry.pluginIdOf(ExtensionType.UI_SURFACE, id)
    fun modelProviders(): List<ModelProviderExtension> =
        ExtensionRegistry.list(ExtensionType.MODEL_PROVIDER)
    fun ragSources(): List<RagSourceExtension> = ExtensionRegistry.list(ExtensionType.RAG_SOURCE)
    fun scheduleTasks(): List<ScheduleTaskExtension> =
        ExtensionRegistry.list(ExtensionType.SCHEDULE_TASK)
    fun fileHandlers(): List<FileHandlerExtension> = ExtensionRegistry.list(ExtensionType.FILE_HANDLER)
    fun codeRuntimes(): List<CodeRuntimeExtension> = ExtensionRegistry.list(ExtensionType.CODE_RUNTIME)
}
