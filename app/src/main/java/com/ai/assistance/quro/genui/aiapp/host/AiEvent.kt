package com.ai.assistance.quro.genui.aiapp.host

import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import kotlinx.serialization.json.JsonElement

/**
 * AI 事件类型
 * 用于 GenUI 组件与宿主应用之间的交互
 */
sealed interface AiEvent {
    /**
     * 导航事件
     *
     * @property route 目标路由
     * @property params 导航参数
     */
    data class Navigate(
        val route: String,
        val params: Map<String, String> = emptyMap()
    ) : AiEvent

    /**
     * 自定义动作
     *
     * @property handlerId 处理器 ID
     * @property payload 动作数据
     */
    data class Custom(
        val handlerId: String,
        val payload: JsonElement
    ) : AiEvent

    /**
     * 事件发送
     *
     * @property name 事件名称
     * @property payload 事件数据
     */
    data class Emit(
        val name: String,
        val payload: JsonElement
    ) : AiEvent

    /**
     * 打开 URL
     *
     * @property url 目标 URL
     */
    data class OpenUrl(
        val url: String
    ) : AiEvent

    /**
     * 复制到剪贴板
     *
     * @property text 要复制的文本
     */
    data class CopyToClipboard(
        val text: String
    ) : AiEvent

    /**
     * 触觉反馈
     *
     * @property type 反馈类型
     */
    data class Haptic(
        val type: String
    ) : AiEvent

    /**
     * 显示对话框
     *
     * @property dialog 对话框组件
     */
    data class ShowDialog(
        val dialog: UIComponent
    ) : AiEvent

    /**
     * 关闭对话框
     */
    data object DismissDialog : AiEvent

    /**
     * 日志
     *
     * @property message 日志消息
     */
    data class Log(
        val message: String
    ) : AiEvent

    /**
     * API 请求
     *
     * @property method 请求方法
     * @property url 请求 URL
     * @property body 请求体
     * @property headers 请求头
     */
    data class RequestApi(
        val method: String,
        val url: String,
        val body: String?,
        val headers: Map<String, String>
    ) : AiEvent

    /**
     * Toast 提示
     */
    data class Toast(
        val message: String,
        val level: String = "info"
    ) : AiEvent

    /**
     * Snackbar 提示
     */
    data class Snackbar(
        val message: String,
        val level: String = "info"
    ) : AiEvent

    /**
     * 滚动到指定位置
     */
    data class ScrollTo(
        val targetId: String,
        val animated: Boolean = true
    ) : AiEvent

    /**
     * 返回
     */
    data object GoBack : AiEvent

    /**
     * 刷新
     */
    data class Refresh(
        val targetId: String? = null
    ) : AiEvent

    /**
     * 加载更多
     */
    data class LoadMore(
        val targetId: String? = null
    ) : AiEvent

    /**
     * 分享
     */
    data class Share(
        val text: String,
        val url: String? = null
    ) : AiEvent
}
