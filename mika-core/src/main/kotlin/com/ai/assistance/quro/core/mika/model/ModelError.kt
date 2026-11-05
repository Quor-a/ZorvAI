package com.ai.assistance.quro.core.mika.model

import com.ai.assistance.quro.core.mika.net.ProxyFailure

sealed class ModelError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Auth : ModelError("API Key 无效或没有权限")
    class QuotaExceeded : ModelError("额度已用完")
    class RateLimited(val retryAfterMs: Long?) : ModelError("请求太频繁,请稍后再试")
    class ContextTooLong : ModelError("对话内容超出模型上下文长度")
    /**
     * 服务商那里没有这个模型(或地址不对,请求的接口不存在);[model] 是请求的模型名,不知道时为空。
     * [detail] 是服务端 JSON 报错里的原话(截短),没有为空:网关、中转服务还会用 404 说别的情况(比如它的上游模型这会儿都不可用),
     * 看原话才分得出是哪一种。
     */
    class ModelNotFound(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "找不到该模型" else "找不到模型「$model」")
    /**
     * 服务商有这个模型,这个账号却用不了(没开通、没权限、没买);[detail] 是服务端的原话(截短),没有为空。
     * 换一个模型或到服务商后台开通才行,重试没用。见 [ModelAccessErrors]。
     */
    class ModelNotAvailable(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "这个账号用不了该模型(没开通或没权限)" else "这个账号用不了模型「$model」(没开通或没权限)")
    /** 服务端明说这个模型不支持调用工具(带工具的请求被拒)。ZorvAI 干活靠工具,它当不了主模型。 */
    class ToolsUnsupported(val model: String = "") :
        ModelError(if (model.isEmpty()) "该模型不支持调用工具" else "模型「$model」不支持调用工具")
    class Network(cause: Throwable? = null) : ModelError("网络连接失败", cause)
    /**
     * 走网络代理时代理那一段出的错(连不上代理,或代理拒了账号密码),不是服务商的问题。见 [com.ai.assistance.quro.core.mika.net.NetProxy]。
     */
    class Proxy(val failure: ProxyFailure, cause: Throwable? = null) : ModelError(
        when (failure) {
            ProxyFailure.Unreachable -> "连不上网络代理"
            ProxyFailure.AuthFailed -> "网络代理拒绝了账号或密码"
        },
        cause,
    )
    /**
     * 流式回复还没收尾(没有结束标记,也没有结束原因)连接就关了:多半是途中的网关、代理空闲超时或网络切换把连接掐断。
     * 回复不完整、不能用,重发同一个请求通常就好;和接口配置无关。
     */
    class StreamCut : ModelError("回复还没传完,连接就被断开了")
    class Server(val status: Int) : ModelError("模型服务出错(HTTP $status)")
    class ContentRefused : ModelError("模型拒绝处理该内容")
    /** ChatGPT 套餐给其他 App 用的额度到了上限:不重试,等额度恢复或换模型。 */
    class ChatGptLimitReached : ModelError("ChatGPT 额度用完了")
    /** 这个 ChatGPT 账号不能在其他 App 里用套餐额度(套餐不符、地区或账号策略不允许):不重试。 */
    class ChatGptNotEligible(val detail: String = "") :
        ModelError(if (detail.isEmpty()) "这个账号不能在其他 App 里用 ChatGPT 额度" else "这个账号不能在其他 App 里用 ChatGPT 额度: $detail")
    /** 登录已失效(换新令牌被拒,或用户在账号里断开了这个 App),要重新登录。[account] 是哪一家的登录(界面上的名字)。 */
    class SignInExpired(val account: String = "ChatGPT") : ModelError("登录已失效,请重新登录")
    /**
     * 用账号登录的(如 xAI)接口回 HTTP 403:登录是成功的,这个订阅档位却不能调这个接口。不重试;
     * 改用这家的 API Key 才行。[account] 是哪一家,[detail] 是服务端的原话(截短),没有为空。
     */
    class AccountPlanNotAllowed(val account: String, val detail: String = "") :
        ModelError("你的${account}订阅档位不能用这个接口")
    class BadResponse(detail: String = "", cause: Throwable? = null) :
        ModelError(if (detail.isEmpty()) "模型返回了无法解析的内容" else "模型返回了无法解析的内容: $detail", cause)
}
