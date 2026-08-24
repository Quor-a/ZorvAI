package com.ai.assistance.quro.core.tools.ui

/**
 * UI 导航事件：AI 调用 ui_* 工具时，通过此密封类通知 ChatScreen 执行界面跳转。
 */
sealed class UiNavigationEvent {
    /** 打开指定界面（editor/terminal/toolbox/knowledge/cms/aci/about/appearance/soul/memory/permission/model_config/voice） */
    data class OpenScreen(val target: String) : UiNavigationEvent()

    /** 切换开关（deepthink/memory） */
    data class ToggleSwitch(val target: String) : UiNavigationEvent()

    /** 打开弹层（model/persona/settings） */
    data class OpenSheet(val target: String) : UiNavigationEvent()

    /** 对话管理（new/clear） */
    data class ChatAction(val action: String) : UiNavigationEvent()

    /** 渲染富卡片 */
    data class RenderCard(val title: String, val content: String, val style: String) : UiNavigationEvent()

    /** 渲染交互组件 */
    data class RenderWidget(val type: String, val id: String, val label: String, val value: String) : UiNavigationEvent()
}
