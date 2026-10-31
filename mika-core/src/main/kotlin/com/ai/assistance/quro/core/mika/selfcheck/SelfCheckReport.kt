package com.ai.assistance.quro.core.mika.selfcheck

import java.net.URLEncoder
import java.util.Locale

/**
 * 把一次自检写成纯文字报告:抬头是版本和设备事实,下面每项一行「编号 名字: 结论 — 事实」,英文键,谁都看得懂。
 * 只写 [DeviceFacts] 和各项的 [CheckResult.detail],不写账号、App 列表、用户的内容、序列号;
 * 最后整段交给 [sanitize](App 里是「复制诊断信息」那一套脱敏),每一项的事实先压成一行、截短。
 */
object SelfCheckReport {
    const val HEADER = "ZorvAI self-check"
    private const val MAX_DETAIL = 200

    fun render(state: SelfCheckState, sanitize: (String) -> String = { it }): String = sanitize(buildString {
        val f = state.facts
        appendLine("$HEADER ${f?.appVersion ?: "?"} (build ${f?.appBuild ?: "?"})")
        if (f != null) {
            appendLine("Device: ${f.modelName}, Android ${f.androidRelease} (SDK ${f.sdk}), ${f.abis.firstOrNull() ?: "unknown ABI"}, RAM ${gb(f.ramBytes)}, ${f.screenWidth}x${f.screenHeight}")
            appendLine("Wireless debugging: ${when (f.wirelessDebugging) { true -> "present"; false -> "missing"; null -> "unknown" }}")
        } else {
            appendLine("Device: unknown")
        }
        appendLine(summary(state))
        appendLine()
        for (r in state.results) appendLine(line(r))
    }.trimEnd() + "\n")

    /** 一项一行:`8 stays alive: FAIL — frames stopped after 6 s`。 */
    fun line(r: CheckResult): String {
        val detail = oneLine(r.detail)
        return "${r.check.number} ${r.check.key}: ${label(r.status)}" + if (detail.isEmpty()) "" else " — $detail"
    }

    fun label(s: CheckStatus): String = when (s) {
        CheckStatus.Pending -> "PENDING"
        CheckStatus.Running -> "RUNNING"
        CheckStatus.Pass -> "PASS"
        CheckStatus.Partial -> "PARTIAL"
        CheckStatus.Fail -> "FAIL"
        CheckStatus.Skipped -> "SKIPPED"
    }

    private fun summary(s: SelfCheckState): String {
        val n = s.results.groupingBy { it.status }.eachCount()
        fun c(st: CheckStatus) = n[st] ?: 0
        val counts = "${c(CheckStatus.Pass)} pass, ${c(CheckStatus.Partial)} partial, ${c(CheckStatus.Fail)} fail, ${c(CheckStatus.Skipped)} skipped"
        val secs = (s.elapsedMs + 500) / 1000
        return if (s.phase == SelfCheckState.Phase.Cancelled) "Result: cancelled after $secs s; $counts" else "Result: $counts in $secs s"
    }

    private fun oneLine(s: String): String {
        val flat = s.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= MAX_DETAIL) flat else flat.take(MAX_DETAIL - 1) + "…"
    }

    private fun gb(bytes: Long): String =
        if (bytes <= 0) "unknown" else String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))

    /** 反馈问题的标题:`[兼容性] Android <版本> <型号>`,型号就是系统给的那个(不另加厂商名)。 */
    fun issueTitle(f: DeviceFacts?): String =
        if (f == null) "[兼容性]" else "[兼容性] Android ${f.androidRelease} ${f.model.trim()}".trim()

    /**
     * 新建问题的地址:选好设备反馈的模板,只预填标题。报告太长,不放进地址里(地址有长度上限),由用户粘贴。
     * [newIssue] 是仓库的新建问题地址。
     */
    fun issueUrl(newIssue: String, f: DeviceFacts?): String =
        "$newIssue?template=$ISSUE_TEMPLATE&title=${encode(issueTitle(f))}"

    const val ISSUE_TEMPLATE = "device-report.yml"

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
