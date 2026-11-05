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

    /**
     * **调用宿主工具**（真实执行）。
     *
     * 对应 DSL 的 `call_tool` 动作（见 `GenUIAction.CallTool`）。与 [sendMessage] 的区别：
     * 后者是把话交给模型再绕一轮，前者是界面直接把工具跑掉 —— 按钮点下去就有真实结果。
     *
     * 默认实现刻意返回一条**可判定的失败 JSON**而不是抛异常：
     *  · 抛异常会让 SDK 侧的点按处理器崩在 UI 线程上（一个按钮点崩整个画布）；
     *  · 返回 JSON 则界面能照常渲染，并且用户看到的是一条明确提示，而不是「点了没反应」。
     *
     * @param name 宿主工具名（与下发给模型的 tools 名单一致）
     * @param argumentsJson 工具入参，JSON 对象文本
     * @return 工具执行结果文本（宿主通常返回 JSON；失败也应返回可读原因，不要抛）
     */
    suspend fun callTool(name: String, argumentsJson: String): String =
        """{"success":false,"error":"当前宿主未接入工具调用","tool":"$name"}"""

    /**
     * 界面上的「工具调用」是否真的可用（宿主实现后返回 true）。
     *
     * 用途：模型会在提示词里看到 `call_tool` 可用，但真跑起来发现宿主没接 ——
     * 那是最难查的一类问题（界面不报错、按钮无反应）。有这条探针，
     * 宿主可以在渲染前把自己的能力如实告诉 SDK，SDK 也能据此降级成 `send_message`。
     */
    fun supportsToolCall(): Boolean = false

    /**
     * 主动查询某个工具的结果（供界面在 `call_tool` 之外按需取用，例如下拉刷新）。
     * 与 [callTool] 等价，单独留一个方法名是为了让宿主的实现意图更清楚。
     */
    suspend fun queryTool(name: String, argumentsJson: String): String = callTool(name, argumentsJson)
}
