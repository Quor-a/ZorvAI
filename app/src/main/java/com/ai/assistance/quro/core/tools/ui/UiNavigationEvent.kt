package com.ai.assistance.quro.core.tools.ui

/**
 * UI 导航事件：AI 调用 ui_control 工具时，通过此密封类通知 ChatScreen 执行界面操作。
 */
sealed class UiNavigationEvent {
    /** 打开指定界面（editor/terminal/toolbox/knowledge/cms/aci/about/appearance/soul/memory/permission/model_config/voice/settings） */
    data class OpenScreen(val target: String) : UiNavigationEvent()

    /** 切换开关（deepthink/memory/permission_*） */
    data class ToggleSwitch(val target: String) : UiNavigationEvent()

    /** 打开弹层（model/persona/settings） */
    data class OpenSheet(val target: String) : UiNavigationEvent()

    /** 对话管理（new/clear） */
    data class ChatAction(val action: String) : UiNavigationEvent()

    /** 渲染富卡片 */
    data class RenderCard(val title: String, val content: String, val style: String) : UiNavigationEvent()

    /** 渲染交互组件 */
    data class RenderWidget(val type: String, val id: String, val label: String, val value: String) : UiNavigationEvent()

    /** 查询组件状态 */
    data class QueryStatus(val component: String) : UiNavigationEvent()

    /** 更新组件属性 */
    data class UpdateComponent(val component: String, val props: Map<String, String>) : UiNavigationEvent()

    /** 滚动到指定位置（top/bottom/组件ID） */
    data class ScrollTo(val target: String) : UiNavigationEvent()

    /** 聚焦到组件 */
    data class FocusComponent(val target: String) : UiNavigationEvent()

    /** 隐藏组件 */
    data class HideComponent(val target: String) : UiNavigationEvent()

    /** 显示组件 */
    data class ShowComponent(val target: String) : UiNavigationEvent()

    /** 页面内导航到指定区块 */
    data class NavigateTo(val target: String) : UiNavigationEvent()

    /** 权限控制 */
    data class PermissionControl(val permission: String, val enabled: Boolean) : UiNavigationEvent()
}
