package com.ai.assistance.quro.plugin.extension

import com.ai.assistance.quro.plugin.contract.ToolArgs
import com.ai.assistance.quro.plugin.contract.ToolResult
import com.ai.assistance.quro.plugin.contract.ToolSpec

/**
 * 扩展点（Extension Point）类型标识。
 *
 * 宿主为每一种扩展点准备一个「收纳槽」，插件往槽里放实现，
 * 宿主在对应场景自动取用 —— 宿主事先不认识这些能力，但能调度它们。
 */
enum class ExtensionType {
    /** AI 工具：注册进 ToolRegistry，LLM 自动发现并调用 ★最常用 */
    AI_TOOL,
    /** ACI 能力：暴露给其他 App / 其他 Agent 调用（走 Binder 语义） */
    ACI_CAPABILITY,
    /** 对话卡片：在聊天气泡里渲染自定义结构化卡片 */
    CHAT_CARD,
    /** 内联可交互组件：用户可直接在气泡里操作 */
    UI_WIDGET,
    /**
     * 界面表面：插件自带一个可在宿主里打开的完整界面（View 树）。
     * 宿主用通用承载 Activity（`com.ai.assistance.quro.ui.PluginSurfaceActivity`）显示，
     * 插件在自己的 [UiSurfaceExtension.build] 里返回要显示的 View。
     */
    UI_SURFACE,
    /** 模型 Provider：新增一种大模型接入方式 */
    MODEL_PROVIDER,
    /** RAG 数据源：新增一种知识库来源 */
    RAG_SOURCE,
    /** 斜杠指令：输入 /xxx 触发 */
    COMMAND,
    /** 设置项：在设置页新增配置项 */
    SETTING,
    /** 定时任务类型：新增一种周期任务执行体 */
    SCHEDULE_TASK,
    /** 消息渠道：新增一个 IM 接入（飞书/QQ/微信之外） */
    CHANNEL,
    /** 文件处理器：新增一种文件类型的打开/预览方式 */
    FILE_HANDLER,
    /** 代码运行时：新增一种脚本语言执行引擎 */
    CODE_RUNTIME,
    /** 语音：TTS / STT 引擎 */
    SPEECH
}

/**
 * 所有扩展的统一标记接口。宿主只按类型收纳，不关心具体子类型。
 */
interface PluginExtension {
    val type: ExtensionType
    /** 扩展 ID，插件内唯一 */
    val id: String
    /** 展示名 */
    val label: String
}

// ==================== 各扩展点的数据载体 ====================

class AiToolExtension(
    override val id: String,
    override val label: String,
    val spec: ToolSpec,
    val executor: com.ai.assistance.quro.plugin.contract.ToolExecutor
) : PluginExtension {
    override val type: ExtensionType = ExtensionType.AI_TOOL
}

class AciCapabilityExtension(
    override val id: String,
    override val label: String,
    val description: String,
    val version: String = "1.0",
    val parameters: List<com.ai.assistance.quro.plugin.contract.ToolParamSpec> = emptyList(),
    val handler: suspend (ToolArgs) -> ToolResult
) : PluginExtension {
    override val type: ExtensionType = ExtensionType.ACI_CAPABILITY
}

class ChatCardExtension(
    override val id: String,
    override val label: String,
    /** 卡片数据 → 宿主可渲染的描述（JSON Schema 在 meta 里） */
    val render: (data: String, ctx: RenderContext) -> RenderedCard
) : PluginExtension {
    override val type: ExtensionType = ExtensionType.CHAT_CARD
}

/**
 * 界面表面扩展：插件给宿主一个「可打开的界面」。
 *
 * 背景：插件没有自己的 Activity（独立 APK 不是系统安装的应用，Activity 起不来），
 * 所以宿主提供一个通用承载 Activity，插件只负责返回 View 树。
 *
 * 用法（插件 Entry.onCreate 内）：
 * <pre>
 * uiSurface("my_panel", "我的面板", "标题") { actCtx, host ->
 *     LinearLayout(actCtx).apply { 在这里构建界面 }
 * }
 * </pre>
 *
 * 注意：Kotlin 的块注释可以嵌套，所以本文件里不要再出现成对的斜杠星号，
 * 否则会把后续声明一起吞进注释（曾导致整个契约模块编译失败）。
 *
 * ⚠ [build] 在主线程调用，可用 Activity Context（能弹 Dialog、能用 WebView 渲染）；
 * 界面销毁时宿主会调 [UiSurfaceExtension.onRelease]，插件应在那里解绑/停止占用资源。
 */
class UiSurfaceExtension(
    override val id: String,
    override val label: String,
    /** 打开界面时显示的标题 */
    val title: String,
    /** Activity Context + 宿主回调 → 返回要显示的 View（null 表示插件拒绝打开） */
    val build: (android.content.Context, SurfaceHost) -> android.view.View?,
    /** 界面关闭回调（主线程）。插件在这里 detach WebView、注销监听等 */
    val onRelease: (() -> Unit)? = null,
) : PluginExtension {
    override val type: ExtensionType = ExtensionType.UI_SURFACE
}

/** 宿主提供给插件界面的回调能力。 */
interface SurfaceHost {
    val pluginId: String
    /** 关闭当前界面 */
    fun close()
    /** 宿主 Toast */
    fun toast(message: String)
    /** 切到主线程执行 */
    fun runOnUi(block: () -> Unit)
}

/**
 * 插件根 View 可选实现此接口，用于接管系统返回键 / 返回手势。
 *
 * 放在契约层而不是宿主 App 里：插件只编译期依赖契约，引用不到宿主 App 的类。
 * 返回 true 表示已消费（不关闭界面），false 交给宿主关闭。
 */
interface SurfaceBackHandler {
    fun onSurfaceBack(): Boolean
}

class CommandExtension(
    override val id: String,
    override val label: String,
    val usage: String,
    val handler: (args: String) -> Boolean
) : PluginExtension {
    override val type: ExtensionType = ExtensionType.COMMAND
}

class SettingExtension(
    override val id: String,
    override val label: String,
    val kind: SettingKind,
    val defaultValue: String = "",
    val options: List<String> = emptyList()
) : PluginExtension {
    override val type: ExtensionType = ExtensionType.SETTING
}

enum class SettingKind { BOOLEAN, TEXT, NUMBER, SELECT, MULTI_SELECT }

class ModelProviderExtension(
    override val id: String,
    override val label: String,
    val models: List<String>,
    val chat: suspend (model: String, messages: String) -> String
) : PluginExtension {
    override val type: ExtensionType = ExtensionType.MODEL_PROVIDER
}

class RagSourceExtension(
    override val id: String,
    override val label: String,
    /** query → 相关文本片段 */
    val retrieve: suspend (query: String, topK: Int) -> List<String>
) : PluginExtension {
    override val type: ExtensionType = ExtensionType.RAG_SOURCE
}

class ScheduleTaskExtension(
    override val id: String,
    override val label: String,
    val run: suspend (payload: String) -> String
) : PluginExtension {
    override val type: ExtensionType = ExtensionType.SCHEDULE_TASK
}

class FileHandlerExtension(
    override val id: String,
    override val label: String,
    val extensions: Set<String>,
    val open: suspend (path: String) -> ToolResult
) : PluginExtension {
    override val type: ExtensionType = ExtensionType.FILE_HANDLER
}

class CodeRuntimeExtension(
    override val id: String,
    override val label: String,
    val language: String,
    val eval: suspend (code: String) -> String
) : PluginExtension {
    override val type: ExtensionType = ExtensionType.CODE_RUNTIME
}

// ==================== 渲染相关 ====================

interface RenderContext {
    /** 让宿主用插件资源渲染一个 View（插件自带 XML 布局用） */
    fun inflate(pluginId: String, layoutName: String): android.view.View?
    fun themeIsDark(): Boolean
    fun density(): Float
}

data class RenderedCard(
    /** 建议宿主用它渲染；为 null 时宿主回退用 title+body 默认样式 */
    val view: android.view.View? = null,
    val title: String? = null,
    val body: String? = null,
    val actions: List<CardAction> = emptyList()
)

data class CardAction(val text: String, val onClick: () -> Unit)
