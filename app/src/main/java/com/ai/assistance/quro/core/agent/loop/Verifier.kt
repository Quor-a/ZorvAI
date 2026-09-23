package com.ai.assistance.quro.core.agent.loop

/**
 * 闭环"校验"阶段的策略接口：判断工具返回是否"符合预期"。
 *
 * 返回空列表 = 通过；返回非空 = 不通过，逐条带严重度。
 * ERROR 级问题会驱动失败决策；WARN 级仅作为成功结果上的备注。
 */
fun interface Verifier {
    fun verify(name: String, arguments: String, raw: String, attempt: Int): List<LoopIssue>
}

/**
 * 默认校验器：做"是否真正执行"的最低限度体检。语义级校验由各场景自行注入。
 *
 * 注意：execOnce 已把"工具跑完但 isSuccess=false"归类为 [ExecResult.Failed]，
 * 走到这里的 [ExecResult.Completed] 都是引擎判定为成功的，所以默认校验主要兜底"空返回"。
 */
object DefaultVerifier : Verifier {
    private val FAIL_MARKERS = listOf("工具执行失败", "工具执行异常", "工具执行超时", "未知工具", "需要权限")

    override fun verify(name: String, arguments: String, raw: String, attempt: Int): List<LoopIssue> {
        val issues = mutableListOf<LoopIssue>()
        if (raw.isBlank()) {
            issues.add(LoopIssue("EMPTY_RESULT", "工具返回为空，可能未真正执行", LoopIssue.Severity.ERROR))
        }
        if (FAIL_MARKERS.any { raw.contains(it) }) {
            issues.add(LoopIssue("FAILURE_MARKER", "返回文本含失败标记，疑似未实际完成", LoopIssue.Severity.ERROR))
        }
        return issues
    }
}
