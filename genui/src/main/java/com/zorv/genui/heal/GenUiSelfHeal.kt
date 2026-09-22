package com.zorv.genui.heal

import com.zorv.genui.protocol.Artifact

/**
 * 错误自愈循环（#630）。
 *
 * 纯生成式能否实用的命根子：模型一次写对的概率有限，必须把运行时错误喂回去自我修复
 * （Self-Debug 模式）。循环硬上限 [maxAttempts]，超阈则交由上层降级，绝不在原地打转烧 token。
 *
 * 用法（由 [com.zorv.genui.controller.GenUiController] 驱动）：
 *   val heal = GenUiSelfHeal(maxAttempts = 3)
 *   heal.delegate = object : SelfHealDelegate {
 *       override fun degrade(id, reason) { controller.setStatus(id, Degraded(reason)) }
 *       override fun injectSystemTurn(feedback) { chat.sendSystem(feedback) }
 *   }
 *   // 收到 GenUiEvent.RuntimeError(artifactId, rev, phase, message, stack, attempt)
 *   heal.onError(artifact, phase, message, stack, attempt)
 */
interface SelfHealDelegate {
    /** 超过自愈上限 → 降级为代码块 */
    fun degrade(artifactId: String, reason: String)

    /** 注入一轮系统消息，触发模型完整重写该卡片 */
    fun injectSystemTurn(feedback: String)
}

class GenUiSelfHeal(private val maxAttempts: Int = 3) {

    var delegate: SelfHealDelegate? = null

    /**
     * 收到一次运行时错误。
     * @param artifact 出错卡片（含 id / rev / code，由上层从 store 或内存取）
     * @param attempt  本轮渲染内已累计的错误次数（来自 shell 的 errorCount）
     */
    fun onError(
        artifact: Artifact,
        phase: String,
        message: String,
        stack: String?,
        attempt: Int
    ) {
        if (attempt >= maxAttempts) {
            delegate?.degrade(artifact.id, "自愈次数超限（$maxAttempts）")
            return
        }
        delegate?.injectSystemTurn(buildFeedback(artifact, phase, message, stack))
    }

    /** 构造注入对话的 feedback，模型据此完整重写（rev+1）。 */
    fun buildFeedback(artifact: Artifact, phase: String, message: String, stack: String?): String {
        val loc = stack?.lines()?.firstOrNull { it.contains("at line") || it.contains(".tsx") || it.contains(".jsx") }
        return buildString {
            appendLine("<runtime-error>")
            appendLine("你生成的卡片（id=${artifact.id}）运行失败：")
            appendLine("阶段：$phase")
            appendLine("错误：$message")
            loc?.let { appendLine("位置：$it") }
            appendLine()
            appendLine("请完整重写该卡片，rev=${artifact.rev + 1}，修复上述错误。")
            appendLine("不要输出 diff 或片段，输出完整代码（含 `export default`）。")
            appendLine("</runtime-error>")
        }.trimIndent()
    }
}
