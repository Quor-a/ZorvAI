package com.ai.assistance.quro.genui.sdk.interaction

import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.dsl.UISpec
import kotlinx.serialization.json.JsonElement

/**
 * Action 宿主接口，定义 SDK 与宿主 App 交互的能力
 */
interface ActionHost {
    fun navigate(route: String, params: Map<String, String>)
    fun handleCustom(handlerId: String, payload: JsonElement): Any?
    fun emit(name: String, payload: JsonElement)
    fun openUrl(url: String)
    fun copyToClipboard(text: String)
    fun haptic(type: String)
    fun showDialog(dialog: UIComponent)
    fun dismissDialog()
    fun log(message: String)
    suspend fun requestApi(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>
    ): JsonElement

    // ============================================================
    // 反馈类
    // ============================================================
    fun showToast(message: String, duration: String = "short", level: String = "info")
    fun showSnackbar(message: String, actionText: String? = null, duration: String = "short", level: String = "info")
    fun vibrate(duration: Long = 50, pattern: List<Long> = emptyList())

    // ============================================================
    // 页面控制类
    // ============================================================
    fun scrollTo(targetId: String, animated: Boolean = true)
    fun goBack()
    fun refresh(targetId: String? = null)
    fun loadMore(targetId: String? = null)

    // ============================================================
    // 分享类
    // ============================================================
    fun share(text: String, title: String? = null, url: String? = null)

    // ============================================================
    // 真实设备动作 v1.9
    // ============================================================
    fun openApp(packageName: String, action: String = "", fallbackUrl: String = "")
    fun playMedia(url: String, type: String = "audio", title: String = "")
    fun stopMedia()
    fun openScreen(spec: UISpec)
    fun openHtml(html: String, title: String = "详情")
    fun executeAction(action: String, params: Map<String, String> = emptyMap())
    fun sendMessage(text: String)
}
