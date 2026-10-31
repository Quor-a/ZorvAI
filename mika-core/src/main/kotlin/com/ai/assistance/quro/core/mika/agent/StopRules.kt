package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.guard.GuardVerdict
import com.ai.assistance.quro.core.mika.model.ModelError
import com.ai.assistance.quro.core.mika.tool.Outcome

/**
 * 停止规则:任务什么时候、以什么结局结束(放弃、守卫叫停、模型出错、没给出回答、步数用完)。办完不在这里:
 * 不带工具调用的回复就是回答,由循环核对后交出。纯函数;结局里给用户看的说明不发给模型。
 */
internal object StopRules {
    /** 一个动作执行完之后:放弃了就按它结束;否则守卫([stop],屏幕守卫或不动屏幕的工具的守卫)叫停了就停。都没有为 null,接着做。 */
    fun afterAction(outcome: Outcome?, stop: GuardVerdict?): TaskState? = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stop?.let(::guard)
    }

    /** 步数用完后的最后一轮调了工具(只给了 end_not_done):放弃按它结束,否则算步数用完。 */
    fun afterFinalTurn(outcome: Outcome?, maxSteps: Int): TaskState = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stepLimit(maxSteps)
    }

    fun stepLimit(maxSteps: Int) = TaskState.Failed("已达到 $maxSteps 步上限,任务没有完成。", FailKind.StepLimit)

    /** 提醒过一次,模型仍然回了空话(或又被输出上限截断)。 */
    fun noAnswer() = TaskState.Failed("模型停止了操作,没有给出回答。", FailKind.Model)

    /** 退回过一次,模型的工具调用又以文字形式回来了(见 [LeakedToolCall]):不把原样的标记当回答交出。 */
    fun toolCallAsText() = TaskState.Failed("模型的工具调用没有被服务端识别,原样作为文字返回了,没能执行。", FailKind.Model)

    /** 模型调用失败(重试过仍不行,或重试也没用)。 */
    fun modelFailed(e: Throwable): TaskState.Failed =
        if (e is ModelError) TaskState.Failed(userReason(e), FailKind.Model)
        else TaskState.Failed("模型调用异常:${e.message ?: e::class.simpleName}", FailKind.Model)

    fun guard(v: GuardVerdict): TaskState.Failed {
        val why = when (v.code) {
            "repeating_action" -> "同一个操作反复执行仍没有效果"
            "no_progress" -> "操作多次后画面一直没有变化"
            "read_loop" -> "页面没有变化,反复读也读不出要找的内容"
            "too_many_failures" -> "操作连续失败"
            "sandbox_loop" -> "同一条命令原样重复仍然失败"
            else -> "触发了操作保护"
        }
        return TaskState.Failed("已安全停止(${v.code}):$why。", FailKind.Guard)
    }
}

internal fun userReason(e: ModelError): String = when (e) {
    is ModelError.Auth -> "模型服务拒绝了请求:API Key 无效或没有权限。请在设置里检查 API Key 是否填写正确、是否有权使用该模型。"
    is ModelError.QuotaExceeded -> "模型账户额度已用完,请检查服务商账户的余额或套餐。"
    is ModelError.ModelNotFound ->
        if (e.model.isEmpty()) "找不到该模型,请到设置 › 模型检查模型名和 Base URL 是否正确。"
        else "模型「${e.model}」在这个服务商那里不存在。去设置 › 模型,检查模型名(要填完整的模型 ID,比如 Gemini 的模型名类似 gemini-flash-latest)或改用对应的预设。"
    is ModelError.ModelNotAvailable ->
        "模型「${e.model.ifEmpty { "当前模型" }}」这个账号没开通或没权限用。去设置 › 模型换一个模型，或到服务商后台开通。"
    is ModelError.ToolsUnsupported ->
        "模型「${e.model.ifEmpty { "当前模型" }}」不支持调用工具,ZorvAI 干活要靠工具。去设置 › 模型换一个会调用工具的模型。"
    is ModelError.ContextTooLong -> "对话内容超出了模型的上下文长度,请换用上下文更长的模型,或把任务拆小一些。"
    is ModelError.ContentRefused -> "模型拒绝处理该内容,请调整任务描述或换一个模型。"
    is ModelError.RateLimited -> "模型服务限流,重试后仍然请求太频繁。请稍后再试,或检查账户的调用频率限制。"
    is ModelError.Network -> "连接模型服务失败,重试后仍然不通。请检查网络和 Base URL。"
    is ModelError.Server -> "模型服务出错(HTTP ${e.status}),请稍后再试。"
    is ModelError.StreamCut ->
        "模型的回复还没传完,连接就在上游被断开了,重试后仍然如此。常见原因是网络不稳,或中转服务、网关的空闲超时太短(模型长时间思考时容易碰上)。请稍后再试。"
    is ModelError.BadResponse -> "${e.message}。请检查 Base URL 和接口类型是否匹配。"
    is ModelError.ChatGptLimitReached ->
        "ChatGPT 额度用完了,稍后再试。用量可以在 ChatGPT 设置 › 用量(chatgpt.com/settings/usage)查看;Plus 每 5 小时的额度和其他用 ChatGPT 登录的 App 共用。也可以先换一个模型。"
    is ModelError.ChatGptNotEligible ->
        "这个账号不能在其他 App 里用 ChatGPT 额度(需要 Plus 或 Pro,并且所在地区和账号设置允许)。${if (e.detail.isEmpty()) "" else "服务端说明:${e.detail}。"}可以换一个模型。"
    is ModelError.SignInExpired -> "ChatGPT 登录已失效,请在设置 › 模型里重新用 ChatGPT 登录。"
}
