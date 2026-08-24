package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.ai.assistance.quro.core.tools.ui.UiNavigationEvent
import org.json.JSONObject

/**
 * UI 导航事件总线：AI 调用 ui_* 工具时，通过此通道通知 ChatScreen 执行界面跳转。
 * ChatScreen 在 LaunchedEffect 中 collect [navEvent] 并执行对应的 UI 操作。
 */
object UiNavigationBus {
    @Volatile var navEvent: UiNavigationEvent? = null
        set(value) {
            field = value
        }
}

/**
 * UI 工具集：让 AI 能操控自己的界面。
 *
 * 命名规律：所有工具以 `ui_` 开头。
 * 分类：
 * - ui_open_*    打开界面
 * - ui_toggle_*  切换开关
 * - ui_open_sheet_*  打开弹层
 * - ui_new_chat / ui_clear_chat  对话管理
 * - ui_card / ui_widget  渲染组件
 */
class QuroUiOpenTool(
    override val name: String,
    private val target: String,
    private val desc: String,
) : QuroTool {
    override val description: String get() = desc
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        UiNavigationBus.navEvent = UiNavigationEvent.OpenScreen(target)
        return "已打开: $target"
    }
}

class QuroUiToggleTool(
    override val name: String,
    private val target: String,
    private val desc: String,
) : QuroTool {
    override val description: String get() = desc
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        UiNavigationBus.navEvent = UiNavigationEvent.ToggleSwitch(target)
        return "已切换: $target"
    }
}

class QuroUiSheetTool(
    override val name: String,
    private val target: String,
    private val desc: String,
) : QuroTool {
    override val description: String get() = desc
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        UiNavigationBus.navEvent = UiNavigationEvent.OpenSheet(target)
        return "已打开弹层: $target"
    }
}

class QuroUiChatTool(
    override val name: String,
    private val action: String,
    private val desc: String,
) : QuroTool {
    override val description: String get() = desc
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        UiNavigationBus.navEvent = UiNavigationEvent.ChatAction(action)
        return "已执行: $action"
    }
}

/**
 * ui_card — 渲染富卡片到对话框。
 * 参数: {"title":"标题", "content":"内容（Markdown）", "style":"info|success|warning|error"}
 */
class QuroUiCardTool : QuroTool {
    override val name = "ui_card"
    override val description = "渲染一张富卡片到对话框。参数: {\"title\":\"标题\", \"content\":\"Markdown内容\", \"style\":\"info|success|warning|error\"}"
    override val parametersJson = """{"type":"object","properties":{"title":{"type":"string"},"content":{"type":"string"},"style":{"type":"string","enum":["info","success","warning","error"]}},"required":["title","content"]}"""

    override fun run(context: Context, arguments: String): String {
        val json = JSONObject(arguments)
        val title = json.optString("title", "")
        val content = json.optString("content", "")
        val style = json.optString("style", "info")
        UiNavigationBus.navEvent = UiNavigationEvent.RenderCard(title, content, style)
        return "已渲染卡片: $title"
    }
}

/**
 * ui_widget — 渲染交互组件到对话框。
 * 参数: {"type":"button|toggle|slider|input|select", "id":"组件ID", "label":"标签", ...}
 */
class QuroUiWidgetTool : QuroTool {
    override val name = "ui_widget"
    override val description = "渲染一个交互组件到对话框。参数: {\"type\":\"button|toggle|slider|input|select\", \"id\":\"唯一ID\", \"label\":\"标签\", \"value\":\"默认值\"}"
    override val parametersJson = """{"type":"object","properties":{"type":{"type":"string","enum":["button","toggle","slider","input","select"]},"id":{"type":"string"},"label":{"type":"string"},"value":{"type":"string"}},"required":["type","id","label"]}"""

    override fun run(context: Context, arguments: String): String {
        val json = JSONObject(arguments)
        val type = json.optString("type", "button")
        val id = json.optString("id", "")
        val label = json.optString("label", "")
        val value = json.optString("value", "")
        UiNavigationBus.navEvent = UiNavigationEvent.RenderWidget(type, id, label, value)
        return "已渲染组件: $type ($label)"
    }
}

/**
 * 注册所有 ui_* 工具到工具注册表。
 */
fun registerUiTools(r: QuroToolRegistry) {
    // ─── 打开界面 ───
    val openTools = listOf(
        Triple("ui_open_editor", "editor", "打开代码编辑器"),
        Triple("ui_open_terminal", "terminal", "打开终端"),
        Triple("ui_open_toolbox", "toolbox", "打开工具箱"),
        Triple("ui_open_knowledge", "knowledge", "打开知识库"),
        Triple("ui_open_cms", "cms", "打开CMS模块"),
        Triple("ui_open_aci", "aci", "打开ACI管理"),
        Triple("ui_open_about", "about", "打开关于页"),
        Triple("ui_open_appearance", "appearance", "打开外观设置"),
        Triple("ui_open_soul", "soul", "打开人格设置"),
        Triple("ui_open_memory", "memory", "打开记忆管理"),
        Triple("ui_open_permission", "permission", "打开权限中心"),
        Triple("ui_open_model_config", "model_config", "打开模型配置"),
        Triple("ui_open_voice", "voice", "打开语音设置"),
    )
    openTools.forEach { (name, target, desc) ->
        r.register(QuroUiOpenTool(name, target, desc))
    }

    // ─── 切换开关 ───
    r.register(QuroUiToggleTool("ui_toggle_deepthink", "deepthink", "切换深度思考开关"))
    r.register(QuroUiToggleTool("ui_toggle_memory", "memory", "切换自动记忆开关"))

    // ─── 弹层 ───
    r.register(QuroUiSheetTool("ui_open_sheet_model", "model", "打开模型选择弹层"))
    r.register(QuroUiSheetTool("ui_open_sheet_persona", "persona", "打开人格选择弹层"))
    r.register(QuroUiSheetTool("ui_open_sheet_settings", "settings", "打开设置面板弹层"))

    // ─── 对话管理 ───
    r.register(QuroUiChatTool("ui_new_chat", "new", "新建对话"))
    r.register(QuroUiChatTool("ui_clear_chat", "clear", "清空当前对话"))

    // ─── 渲染组件 ───
    r.register(QuroUiCardTool())
    r.register(QuroUiWidgetTool())
}
