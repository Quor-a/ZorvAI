package com.ai.assistance.quro.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * 跨组件桥：当工具（open_web）或聊天内链接请求打开网页时，
 * 向 UI 层发送 URL，由 ChatScreen 用内置 WebView 浏览器呈现，
 * 而不是跳转系统浏览器。
 */
object QuroBrowserBridge {
    private val _requests = Channel<String>(Channel.UNLIMITED)
    val requests: ReceiveChannel<String> get() = _requests

    /** 请求在应用内置浏览器中打开一个 URL。 */
    fun open(url: String) {
        _requests.trySend(url)
    }
}
