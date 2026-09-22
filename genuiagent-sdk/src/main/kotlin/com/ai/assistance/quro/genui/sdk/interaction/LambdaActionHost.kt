package com.ai.assistance.quro.genui.sdk.interaction

import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Lambda 方式的 ActionHost 实现，支持 DSL 风格的构建器。
 *
 * 通过 lambdaActionHost DSL 函数可以便捷地注册各种动作的处理器：
 *
 * ```kotlin
 * lambdaActionHost {
 *     onNavigate { route, params ->
 *         // 处理导航
 *     }
 *     onOpenUrl { url ->
 *         // 打开链接
 *     }
 *     onApi { url, method, body, headers ->
 *         // 发起 API 请求
 *     }
 *     onCustom("myHandler") { payload ->
 *         // 处理自定义动作
 *     }
 * }
 * ```
 *
 * @param navigateFn 导航处理函数
 * @param customFn 自定义动作处理函数
 * @param emitFn 事件发送处理函数
 * @param openUrlFn 打开链接处理函数
 * @param copyToClipboardFn 复制到剪贴板处理函数
 * @param hapticFn 触觉反馈处理函数
 * @param requestApiFn API 请求处理函数（挂起）
 * @param showDialogFn 显示对话框处理函数
 * @param dismissDialogFn 关闭对话框处理函数
 * @param logFn 日志输出处理函数
 * @param showToastFn Toast 提示处理函数
 * @param showSnackbarFn Snackbar 提示处理函数
 * @param vibrateFn 震动处理函数
 * @param scrollToFn 滚动处理函数
 * @param goBackFn 返回处理函数
 * @param refreshFn 刷新处理函数
 * @param loadMoreFn 加载更多处理函数
 * @param shareFn 分享处理函数
 */
class LambdaActionHost private constructor(
    private val navigateFn: (route: String, params: Map<String, String>) -> Unit,
    private val customFn: (handlerId: String, payload: JsonElement) -> Any?,
    private val emitFn: (name: String, payload: JsonElement) -> Unit,
    private val openUrlFn: (url: String) -> Unit,
    private val copyToClipboardFn: (text: String) -> Unit,
    private val hapticFn: (type: String) -> Unit,
    private val requestApiFn: suspend (method: String, url: String, body: String?, headers: Map<String, String>) -> JsonElement,
    private val showDialogFn: (dialog: UIComponent) -> Unit,
    private val dismissDialogFn: () -> Unit,
    private val logFn: (message: String) -> Unit,
    private val showToastFn: (message: String, duration: String, level: String) -> Unit,
    private val showSnackbarFn: (message: String, actionText: String?, duration: String, level: String) -> Unit,
    private val vibrateFn: (duration: Long, pattern: List<Long>) -> Unit,
    private val scrollToFn: (targetId: String, animated: Boolean) -> Unit,
    private val goBackFn: () -> Unit,
    private val refreshFn: (targetId: String?) -> Unit,
    private val loadMoreFn: (targetId: String?) -> Unit,
    private val shareFn: (text: String, title: String?, url: String?) -> Unit
) : ActionHost {

    override fun navigate(route: String, params: Map<String, String>) {
        navigateFn(route, params)
    }

    override fun handleCustom(handlerId: String, payload: JsonElement): Any? {
        return customFn(handlerId, payload)
    }

    override fun emit(name: String, payload: JsonElement) {
        emitFn(name, payload)
    }

    override fun openUrl(url: String) {
        openUrlFn(url)
    }

    override fun copyToClipboard(text: String) {
        copyToClipboardFn(text)
    }

    override fun haptic(type: String) {
        hapticFn(type)
    }

    override suspend fun requestApi(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>
    ): JsonElement {
        return requestApiFn(method, url, body, headers)
    }

    override fun showDialog(dialog: UIComponent) {
        showDialogFn(dialog)
    }

    override fun dismissDialog() {
        dismissDialogFn()
    }

    override fun log(message: String) {
        logFn(message)
    }

    override fun showToast(message: String, duration: String, level: String) {
        showToastFn(message, duration, level)
    }

    override fun showSnackbar(message: String, actionText: String?, duration: String, level: String) {
        showSnackbarFn(message, actionText, duration, level)
    }

    override fun vibrate(duration: Long, pattern: List<Long>) {
        vibrateFn(duration, pattern)
    }

    override fun scrollTo(targetId: String, animated: Boolean) {
        scrollToFn(targetId, animated)
    }

    override fun goBack() {
        goBackFn()
    }

    override fun refresh(targetId: String?) {
        refreshFn(targetId)
    }

    override fun loadMore(targetId: String?) {
        loadMoreFn(targetId)
    }

    override fun share(text: String, title: String?, url: String?) {
        shareFn(text, title, url)
    }

    /**
     * LambdaActionHost 构建器，提供 DSL 风格的注册方法。
     */
    class Builder {
        private var navigate: (String, Map<String, String>) -> Unit = { _, _ -> }
        private var custom: (String, JsonElement) -> Any? = { _, _ -> null }
        private var emit: (String, JsonElement) -> Unit = { _, _ -> }
        private var openUrl: (String) -> Unit = { }
        private var copyToClipboard: (String) -> Unit = { }
        private var haptic: (String) -> Unit = { }
        private var requestApi: suspend (method: String, url: String, body: String?, headers: Map<String, String>) -> JsonElement = { _, _, _, _ -> kotlinx.serialization.json.JsonNull }
        private var showDialog: (UIComponent) -> Unit = { }
        private var dismissDialog: () -> Unit = { }
        private var log: (String) -> Unit = { println("[GenUI] $it") }
        private var showToast: (message: String, duration: String, level: String) -> Unit = { _, _, _ -> }
        private var showSnackbar: (message: String, actionText: String?, duration: String, level: String) -> Unit = { _, _, _, _ -> }
        private var vibrate: (duration: Long, pattern: List<Long>) -> Unit = { _, _ -> }
        private var scrollTo: (targetId: String, animated: Boolean) -> Unit = { _, _ -> }
        private var goBack: () -> Unit = { }
        private var refresh: (targetId: String?) -> Unit = { }
        private var loadMore: (targetId: String?) -> Unit = { }
        private var share: (text: String, title: String?, url: String?) -> Unit = { _, _, _ -> }

        /**
         * 注册导航处理器
         */
        fun onNavigate(block: (route: String, params: Map<String, String>) -> Unit): Builder {
            navigate = block
            return this
        }

        /**
         * 注册自定义动作处理器
         */
        fun onCustom(block: (handlerId: String, payload: JsonElement) -> Any?): Builder {
            custom = block
            return this
        }

        /**
         * 注册事件发送处理器
         */
        fun onEmit(block: (name: String, payload: JsonElement) -> Unit): Builder {
            emit = block
            return this
        }

        /**
         * 注册打开链接处理器
         */
        fun onOpenUrl(block: (url: String) -> Unit): Builder {
            openUrl = block
            return this
        }

        /**
         * 注册复制到剪贴板处理器
         */
        fun onCopyToClipboard(block: (text: String) -> Unit): Builder {
            copyToClipboard = block
            return this
        }

        /**
         * 注册触觉反馈处理器
         */
        fun onHaptic(block: (type: String) -> Unit): Builder {
            haptic = block
            return this
        }

        /**
         * 注册 API 请求处理器（挂起函数）
         */
        fun onApi(block: suspend (method: String, url: String, body: String?, headers: Map<String, String>) -> JsonElement): Builder {
            requestApi = block
            return this
        }

        /**
         * 注册显示对话框处理器
         */
        fun onShowDialog(block: (dialog: UIComponent) -> Unit): Builder {
            showDialog = block
            return this
        }

        /**
         * 注册关闭对话框处理器
         */
        fun onDismissDialog(block: () -> Unit): Builder {
            dismissDialog = block
            return this
        }

        /**
         * 注册日志输出处理器
         */
        fun onLog(block: (message: String) -> Unit): Builder {
            log = block
            return this
        }

        /**
         * 注册 Toast 提示处理器
         */
        fun onShowToast(block: (message: String, duration: String, level: String) -> Unit): Builder {
            showToast = block
            return this
        }

        /**
         * 注册 Snackbar 提示处理器
         */
        fun onShowSnackbar(block: (message: String, actionText: String?, duration: String, level: String) -> Unit): Builder {
            showSnackbar = block
            return this
        }

        /**
         * 注册震动处理器
         */
        fun onVibrate(block: (duration: Long, pattern: List<Long>) -> Unit): Builder {
            vibrate = block
            return this
        }

        /**
         * 注册滚动处理器
         */
        fun onScrollTo(block: (targetId: String, animated: Boolean) -> Unit): Builder {
            scrollTo = block
            return this
        }

        /**
         * 注册返回处理器
         */
        fun onGoBack(block: () -> Unit): Builder {
            goBack = block
            return this
        }

        /**
         * 注册刷新处理器
         */
        fun onRefresh(block: (targetId: String?) -> Unit): Builder {
            refresh = block
            return this
        }

        /**
         * 注册加载更多处理器
         */
        fun onLoadMore(block: (targetId: String?) -> Unit): Builder {
            loadMore = block
            return this
        }

        /**
         * 注册分享处理器
         */
        fun onShare(block: (text: String, title: String?, url: String?) -> Unit): Builder {
            share = block
            return this
        }

        /**
         * 构建 LambdaActionHost 实例
         */
        fun build(): LambdaActionHost {
            return LambdaActionHost(
                navigateFn = navigate,
                customFn = custom,
                emitFn = emit,
                openUrlFn = openUrl,
                copyToClipboardFn = copyToClipboard,
                hapticFn = haptic,
                requestApiFn = requestApi,
                showDialogFn = showDialog,
                dismissDialogFn = dismissDialog,
                logFn = log,
                showToastFn = showToast,
                showSnackbarFn = showSnackbar,
                vibrateFn = vibrate,
                scrollToFn = scrollTo,
                goBackFn = goBack,
                refreshFn = refresh,
                loadMoreFn = loadMore,
                shareFn = share
            )
        }
    }

    // 真实设备动作 v1.9（Lambda 宿主的默认空实现）
    override fun openApp(packageName: String, action: String, fallbackUrl: String) {}
    override fun playMedia(url: String, type: String, title: String) {}
    override fun stopMedia() {}
    override fun openScreen(spec: com.ai.assistance.quro.genui.sdk.dsl.UISpec) {}
    override fun openHtml(html: String, title: String) {}
    override fun executeAction(action: String, params: Map<String, String>) {}
    override fun sendMessage(text: String) {}
}

/**
 * DSL 风格的 LambdaActionHost 构建函数。
 *
 * 使用示例：
 * ```kotlin
 * val host = lambdaActionHost {
 *     onNavigate { route, params ->
 *         // 处理导航
 *     }
 *     onOpenUrl { url ->
 *         // 打开链接
 *     }
 * }
 * ```
 */
fun lambdaActionHost(block: LambdaActionHost.Builder.() -> Unit): LambdaActionHost {
    val builder = LambdaActionHost.Builder()
    builder.block()
    return builder.build()
}