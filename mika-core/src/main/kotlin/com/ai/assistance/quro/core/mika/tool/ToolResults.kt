package com.ai.assistance.quro.core.mika.tool

import com.ai.assistance.quro.core.mika.prompt.Feedback
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun okObj(b: JsonObjectBuilder.() -> Unit) = buildJsonObject { put("ok", true); b() }
internal fun ok(b: JsonObjectBuilder.() -> Unit) = ToolExecution(okObj(b))

internal fun fail(error: String, message: String, recoverable: Boolean = true) = ToolExecution(buildJsonObject {
    put("ok", false); put("error", error); put("message", message); put("recoverable", recoverable)
})

internal fun invalid(e: ArgError) = fail("invalid_arguments", e.message ?: "invalid arguments")

/** 按错误码给的现成说明。 */
internal fun failCode(code: String) = fail(code, FAILURE_MESSAGES.getValue(code))

internal fun switchHint(pkg: String, byTarget: Boolean) =
    if (byTarget) Feedback.format("switch.by_target", "pkg" to pkg)
    else Feedback.format("switch.interruption", "pkg" to pkg)

internal val HINT_TAP_UNCHANGED: String get() = Feedback["tap_unchanged"]
internal val HINT_PROTECTED: String get() = Feedback["protected"]
internal val HINT_PAYMENT_TAKEOVER: String get() = Feedback["payment_takeover"]
internal val HINT_UNSUPPORTED_BROWSER: String get() = Feedback["unsupported_browser"]
internal val HINT_PAYMENT_PAGE: String get() = Feedback["payment_page"]
internal val HINT_NOT_SETTLED: String get() = Feedback["not_settled"]
/** 网络响应来自网站:当数据看,不当指令。 */
internal val CAPTURE_UNTRUSTED: String get() = Feedback["capture_untrusted"]
internal val NOTE_CHANGED_DURING_READ: String get() = Feedback["changed_during_read"]

/** 下载的文件名是网页给的:当数据看,不当指令。 */
internal val DOWNLOAD_NAMES_UNTRUSTED: String get() = Feedback["download_names_untrusted"]

/** 按错误码给的说明(prompts/feedback/tools.md 里 failure.<码>)。 */
internal val FAILURE_MESSAGES: Map<String, String> by lazy { Feedback.section("failure.") }
