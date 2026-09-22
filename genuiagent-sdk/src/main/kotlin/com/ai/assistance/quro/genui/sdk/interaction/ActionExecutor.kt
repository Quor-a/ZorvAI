package com.ai.assistance.quro.genui.sdk.interaction

import com.ai.assistance.quro.genui.sdk.dsl.GenUIAction
import com.ai.assistance.quro.genui.sdk.dsl.GenUIEvent
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.skill.GlobalDynamicRegistry
import com.ai.assistance.quro.genui.sdk.state.DialogHolder
import com.ai.assistance.quro.genui.sdk.state.GenUIStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Action 执行器，负责执行 UI 事件触发的动作列表
 */
class ActionExecutor(
    private val host: ActionHost,
    private val state: GenUIStateStore,
    private val dialogHolder: DialogHolder,
    private val scope: CoroutineScope
) {
    fun execute(actions: List<GenUIAction>, component: UIComponent? = null) {
        actions.forEach { action ->
            executeOne(action, component)
        }
    }

    fun execute(event: GenUIEvent?, component: UIComponent? = null) {
        if (event == null) return
        execute(event.actions, component)
    }

    /**
     * 为组件的指定事件创建点击处理器
     * @return 如果事件不存在则返回 null
     */
    fun handlerFor(component: UIComponent, eventName: String): (() -> Unit)? {
        val event = component.events[eventName] ?: return null
        return { execute(event.actions, component) }
    }

    private fun executeOne(action: GenUIAction, component: UIComponent?) {
        when (action) {
            is GenUIAction.Navigate -> host.navigate(action.route, action.params)
            is GenUIAction.UpdateState -> state.set(action.key, action.value)
            is GenUIAction.ToggleState -> state.toggle(action.key)
            is GenUIAction.ShowDialog -> dialogHolder.show(action.dialog)
            is GenUIAction.DismissDialog -> dialogHolder.dismiss()
            is GenUIAction.Haptic -> host.haptic(action.type)
            is GenUIAction.Emit -> host.emit(action.name, action.payload)
            is GenUIAction.OpenUrl -> host.openUrl(action.url)
            is GenUIAction.CopyToClipboard -> host.copyToClipboard(action.text)
            is GenUIAction.Custom -> host.handleCustom(action.handlerId, action.collectedPayload)
            is GenUIAction.Log -> host.log(action.message)
            is GenUIAction.CallApi -> scope.launch {
                host.requestApi(action.method, action.url, action.body, action.headers)
            }
            // 反馈类
            is GenUIAction.Toast -> host.showToast(action.message, action.duration, action.level)
            is GenUIAction.Snackbar -> host.showSnackbar(action.message, action.actionText, action.duration, action.level)
            is GenUIAction.Vibrate -> host.vibrate(action.duration, action.pattern)
            // 组件操作类
            is GenUIAction.RegisterComponent -> {
                GlobalDynamicRegistry.get()?.registerTemplate(
                    name = action.name,
                    description = action.description,
                    template = action.template,
                    category = action.category,
                    variables = action.variables
                )
            }
            is GenUIAction.SetState -> state.set(action.key, action.value)
            is GenUIAction.ScrollTo -> host.scrollTo(action.targetId, action.animated)
            // 分享类
            is GenUIAction.Share -> host.share(action.text, action.title, action.url)
            // 真实设备动作 v1.9
            is GenUIAction.OpenApp -> host.openApp(action.packageName, action.action, action.fallbackUrl)
            is GenUIAction.PlayMedia -> host.playMedia(action.url, action.type, action.title)
            is GenUIAction.StopMedia -> host.stopMedia()
            is GenUIAction.OpenScreen -> host.openScreen(action.spec)
            is GenUIAction.OpenHtml -> host.openHtml(action.html, action.title)
            is GenUIAction.Execute -> host.executeAction(action.action, action.params)
            is GenUIAction.SendMessage -> host.sendMessage(action.text)
            // 页面控制类
            is GenUIAction.GoBack -> host.goBack()
            is GenUIAction.Refresh -> host.refresh(action.targetId)
            is GenUIAction.LoadMore -> host.loadMore(action.targetId)
        }
    }
}
