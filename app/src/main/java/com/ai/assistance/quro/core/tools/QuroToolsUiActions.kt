package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.cards.QuroChatCard

/**
 * UI 动作工具层：把对话框内部可打开的界面 / 弹层 / 开关 注册为 AI 可调用工具。
 *
 * 设计：UI 与 AI 工具原本正交——UI 走 `sheet` / `showX` 状态，工具走 `QuroToolRegistry`。
 * 本层用一座「桥」[QuroUiActionBridge] 把两者接起来：工具 `run()` 仅把 action 名回调给
 * 对话框组合作用域（ChatScreen 注入的 `dispatch`），由对话框设置对应状态真正打开界面。
 * 这样 AI 就能「打开 WPS 文档中心 / 切换深度思考 / 清空对话」等，等价于点对应按钮。
 *
 * 工具名约定：
 * - `ui_open_<surface>`：打开某个全屏界面或底部弹层（如 ui_open_onlyoffice）
 * - `ui_toggle_<switch>`：切换某个开关（如 ui_toggle_deepthink）
 * - `ui_clear_chat` / `ui_new_chat`：清空 / 新建对话
 * 全部进入 [coreSpecs] 默认下发集，并由 [appendCapabilityAwareness] 自动带进系统提示词（同源）。
 */
object QuroUiActionBridge {
    /** 由 ChatScreen 注入：action -> 在 UI 层执行对应打开/切换。未连接时工具返回提示。 */
    var dispatch: ((action: String) -> Unit)? = null

    /**
     * 由 ChatScreen 注入：AI 经 ui_widget / ui_card 下发的富组件 -> 挂到当前助手消息气泡。
     * 工具在运行时调用；未连接时退化为全局 QuroChatCardStore（底部卡片栏）兜底，不丢卡片。
     */
    var onCard: ((card: QuroChatCard) -> Unit)? = null
}

private data class UiActionSpec(
    val action: String,
    val label: String,
    val desc: String,
)

/** 全部可被 AI 打开/控制的界面、弹层与开关（对话框内 UI 组件 → 工具）。 */
private val UI_ACTIONS = listOf(
    UiActionSpec("ui_open_onlyoffice", "ONLYOFFICE 文档", "打开 ONLYOFFICE Documents（开源移动办公套件，可离线编辑 Office 文档；已整合原文档中心）"),
    UiActionSpec("ui_open_knowledge", "知识库", "打开知识库，浏览/导入/检索本地知识文档"),
    UiActionSpec("ui_open_terminal", "终端", "打开应用内终端 shell"),
    UiActionSpec("ui_open_editor", "代码编辑器", "打开内置代码编辑器"),
    UiActionSpec("ui_open_toolbox", "工具箱", "打开工具箱（文件管理/浏览器/IDE）"),
    UiActionSpec("ui_open_plugins", "插件", "打开插件管理"),
    UiActionSpec("ui_open_skills", "技能", "打开技能 SKILL 管理（查看/新增/编辑/启用用户自定义技能）"),
    UiActionSpec("ui_open_cms", "能力模块(CMS)", "打开 CMS 能力模块"),
    UiActionSpec("ui_open_permission", "权限中心", "打开 CapOS 权限中心（L1-L4）"),
    UiActionSpec("ui_open_model_config", "模型配置", "打开模型配置"),
    UiActionSpec("ui_open_voice", "语音服务", "打开语音服务设置（TTS/STT）"),
    UiActionSpec("ui_open_tts", "文本转语音", "打开 TTS 设置"),
    UiActionSpec("ui_open_stt", "语音转文本", "打开 STT 设置"),
    UiActionSpec("ui_open_voice_service", "语音服务(高级)", "打开语音服务高级设置"),
    UiActionSpec("ui_open_about", "关于", "打开关于页"),
    UiActionSpec("ui_open_appearance", "外观", "打开外观设置"),
    UiActionSpec("ui_open_soul", "灵魂注入", "打开人格/灵魂注入设置"),
    UiActionSpec("ui_open_memory", "记忆管理", "打开记忆管理对话框"),
    UiActionSpec("ui_open_sheet_model", "模型选择弹层", "打开底部模型选择弹层"),
    UiActionSpec("ui_open_sheet_persona", "人格选择弹层", "打开底部人格选择弹层"),
    UiActionSpec("ui_open_sheet_settings", "设置弹层", "打开底部设置弹层"),
    UiActionSpec("ui_open_sheet_upload", "工具面板", "打开 +工具 上传/工具面板"),
    UiActionSpec("ui_open_sheet_voice", "语音面板", "打开语音面板（TTS/STT）"),
    UiActionSpec("ui_open_upload", "上传文件", "打开文件上传选择（图片/文件/视频）"),
    UiActionSpec("ui_open_import_tool", "导入工具", "打开导入工具对话框（AI 自写/粘贴 JSON）"),
    UiActionSpec("ui_open_ai_search", "AI 搜索", "打开 AI 浏览器·联网搜索对话框"),
    UiActionSpec("ui_open_doc_generate", "文档生成", "打开文档生成对话框（生成 WPS/Office）"),
    UiActionSpec("ui_toggle_deepthink", "切换深度思考", "切换对话框「深度思考」开关（更慢但更深）"),
    UiActionSpec("ui_toggle_memory", "切换自动记忆", "切换「AI 自动保存记忆」开关"),
    UiActionSpec("ui_clear_chat", "清空对话", "清空当前对话消息"),
    UiActionSpec("ui_new_chat", "新对话", "新建一个对话"),
)

private class UiActionTool(private val spec: UiActionSpec) : QuroTool {
    override val name = spec.action
    override val description = spec.desc
    override val parametersJson = """{"type":"object","properties":{"note":{"type":"string","description":"可选备注，说明为何打开此界面"}}}"""
    override fun run(context: Context, arguments: String): String {
        val d = QuroUiActionBridge.dispatch
        return if (d != null) {
            d(spec.action)
            "已执行 UI 动作：${spec.label}"
        } else {
            "UI 动作桥未连接（对话框未就绪，无法打开界面）"
        }
    }
}

/** 全部 UI 动作工具实例（供 [buildQuroRegistry] 注册）。 */
val allUiActionTools: List<QuroTool> = UI_ACTIONS.map { UiActionTool(it) }

/** UI 动作工具名集合（供 [QuroToolRegistry.coreSpecs] 纳入默认下发集）。 */
fun uiActionToolNames(): Set<String> = UI_ACTIONS.map { it.action }.toSet()
