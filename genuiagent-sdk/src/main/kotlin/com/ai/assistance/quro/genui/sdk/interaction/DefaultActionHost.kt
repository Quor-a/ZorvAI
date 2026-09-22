package com.ai.assistance.quro.genui.sdk.interaction

import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.dsl.UISpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * 默认动作宿主实现。
 *
 * 提供 ActionHost 接口的默认空实现，可作为基础实现类，
 * 宿主 App 可以继承此类并按需重写需要处理的方法。
 *
 * 默认实现中，[log] 方法会将日志输出到标准输出。
 */
open class DefaultActionHost : ActionHost {

    override fun navigate(route: String, params: Map<String, String>) {}

    override fun handleCustom(handlerId: String, payload: JsonElement): Any? = null

    override fun emit(name: String, payload: JsonElement) {}

    override fun openUrl(url: String) {}

    override fun copyToClipboard(text: String) {}

    override fun haptic(type: String) {}

    override suspend fun requestApi(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>
    ): JsonElement = JsonNull

    override fun showDialog(dialog: UIComponent) {}

    override fun dismissDialog() {}

    override fun log(message: String) {
        println("[GenUI] $message")
    }

    // 反馈类
    override fun showToast(message: String, duration: String, level: String) {}
    override fun showSnackbar(message: String, actionText: String?, duration: String, level: String) {}
    override fun vibrate(duration: Long, pattern: List<Long>) {}

    // 页面控制类
    override fun scrollTo(targetId: String, animated: Boolean) {}
    override fun goBack() {}
    override fun refresh(targetId: String?) {}
    override fun loadMore(targetId: String?) {}

    // 分享类
    override fun share(text: String, title: String?, url: String?) {}

    // 真实设备动作 v1.9
    override fun openApp(packageName: String, action: String, fallbackUrl: String) {}
    override fun playMedia(url: String, type: String, title: String) {}
    override fun stopMedia() {}
    override fun openScreen(spec: UISpec) {}
    override fun openHtml(html: String, title: String) {}
    override fun executeAction(action: String, params: Map<String, String>) {}
    override fun sendMessage(text: String) {}
}
