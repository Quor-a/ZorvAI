package com.ai.assistance.quro.core.assistant

/**
 * 助手能力统一结果封套。
 *
 * 范式来源：Breeno 的 `results.proto` —— `SealedResult{resultCode, data, errorCode, message}`。
 * 小布把**每一种失败都编了稳定码**并按子系统分类，好处是：日志可 grep 归因、UI 能按码给不同
 * 处置建议、跨进程回执不会只剩一句「失败」。
 *
 * 我们的铁律（与 [com.ai.assistance.quro.terminal.ui.TerminalFailureCode] 一致）：
 * **码只加不改**。任何已发布的码都要保持语义不变，否则历史诊断包再也对不上。
 */
object AssistantCodes {
    const val OK = "ok"

    // 编排层
    const val SKILL_NOT_FOUND = "skill-not-found"
    const val SKILL_DISABLED = "skill-disabled"
    const val SKILL_BAD_ARGS = "skill-bad-args"
    const val SKILL_FAILED = "skill-failed"
    const val NO_TEXT = "no-text"

    // 前置条件
    const val PERMISSION_DENIED = "permission-denied"
    const val ENV_NOT_READY = "env-not-ready"
    const val BUSY = "busy"
    const val TIMEOUT = "timeout"
    const val UNSUPPORTED = "unsupported"
    const val RATE_LIMITED = "rate-limited"

    // 外部任务中转
    const val RELAY_REJECTED = "relay-rejected"
    const val RELAY_DUPLICATE = "relay-duplicate"

    const val INTERNAL = "internal"
}

/** 结果里可以点的动作（对标小布卡片 `pantanal_card_action`：结果自带后续动作）。 */
data class AssistantAction(
    val id: String,
    val label: String,
    /** 见 [KIND_SKILL] / [KIND_DEEPLINK] / [KIND_DISMISS] */
    val kind: String = KIND_SKILL,
    /** kind=skill 时是技能 id；kind=deeplink 时是 URI */
    val target: String = "",
    val primary: Boolean = false,
) {
    companion object {
        const val KIND_SKILL = "skill"
        const val KIND_DEEPLINK = "deeplink"
        const val KIND_DISMISS = "dismiss"

        fun dismiss() = AssistantAction("dismiss", "关闭", KIND_DISMISS)
    }
}

/**
 * 一次能力调用的结果。
 *
 * @param ok 是否成功（`code == AssistantCodes.OK` 时恒为 true，二者不会互相矛盾）
 * @param elapsedMs 执行耗时，用于排查「慢在哪一步」
 */
data class AssistantResult(
    val ok: Boolean,
    val code: String,
    val message: String = "",
    val data: Map<String, Any?> = emptyMap(),
    val actions: List<AssistantAction> = emptyList(),
    val skillId: String = "",
    val elapsedMs: Long = 0L,
) {
    /** 单行摘要：写进 QuroDiag / 广播回执 / 诊断包。 */
    fun wire(): String {
        val sb = StringBuilder()
        sb.append("code=").append(code)
        sb.append(" ok=").append(ok)
        if (skillId.isNotEmpty()) sb.append(" skill=").append(skillId)
        if (elapsedMs > 0) sb.append(" ms=").append(elapsedMs)
        if (message.isNotEmpty()) sb.append(" msg=").append(message.replace('\n', ' ').take(200))
        if (data.isNotEmpty()) {
            sb.append(" data=")
            sb.append(data.entries.joinToString(",") { "${it.key}:${it.value}" }.take(300))
        }
        return sb.toString()
    }

    companion object {
        fun ok(
            skillId: String = "",
            message: String = "",
            data: Map<String, Any?> = emptyMap(),
            actions: List<AssistantAction> = emptyList(),
            elapsedMs: Long = 0L,
        ) = AssistantResult(true, AssistantCodes.OK, message, data, actions, skillId, elapsedMs)

        fun fail(
            code: String,
            message: String,
            skillId: String = "",
            data: Map<String, Any?> = emptyMap(),
            actions: List<AssistantAction> = emptyList(),
            elapsedMs: Long = 0L,
        ) = AssistantResult(false, code, message, data, actions, skillId, elapsedMs)

        fun internalError(skillId: String, t: Throwable, elapsedMs: Long = 0L) = fail(
            AssistantCodes.INTERNAL,
            (t.message ?: t.javaClass.simpleName),
            skillId = skillId,
            elapsedMs = elapsedMs,
        )
    }
}

/** 调用来源：决定是否需要回执、是否需要把结果念出来、是否允许改系统状态。 */
enum class AssistantSource(val wire: String) {
    /** 小布式文本指令（显式广播 / 深链），外部可调 */
    DIRECTIVE("directive"),

    /** 系统分享 / 选中文本处理 */
    SHARE("share"),

    /** 应用内技能广场点按 */
    PLAZA("plaza"),

    /** 快捷磁贴 / 快捷方式 */
    TILE("tile"),

    /** 语音（唤醒词 / 长按助手键） */
    VOICE("voice"),

    /** AI 工具调用（模型自己编排） */
    AI("ai"),

    /** 后台任务中转（外部提交长任务） */
    RELAY("relay"),
}

/** 一轮能力调用的入参。 */
data class AssistantRequest(
    val text: String = "",
    val args: Map<String, String> = emptyMap(),
    val source: AssistantSource = AssistantSource.PLAZA,
    /** 分享/选中的原始文本（与 text 分开：text 可能是清洗后的指令） */
    val raw: String = "",
    /** 回执广播的 requestId（外部 relay 用） */
    val requestId: String = "",
) {
    fun arg(key: String): String? = args[key]?.takeIf { it.isNotBlank() }
}
