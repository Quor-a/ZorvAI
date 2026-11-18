package com.ai.assistance.quro.etalib.core

private const val UNKNOWN_LOG_TOKEN = "unknown"

/** 返回不会携带异常消息或运行时数据的稳定异常类型。 */
internal fun Throwable.safeLogType(): String =
    javaClass.simpleName.takeIf { it.isNotBlank() } ?: Throwable::class.java.simpleName

/** 保留失败位置与原因类型，禁止异常消息、载荷或 suppressed 内容进入诊断日志。 */
internal fun Throwable.safeStackTrace(): String = buildString {
    var failure: Throwable? = this@safeStackTrace
    val visited = java.util.IdentityHashMap<Throwable, Boolean>()
    repeat(3) {
        val current = failure ?: return@buildString
        if (visited.put(current, true) != null) return@buildString
        if (isNotEmpty()) append("\nCaused by: ")
        append(current.safeLogType().take(128))
        val frames = current.stackTrace
        frames.take(16).forEach { frame ->
            append("\n at ")
            append(frame.className.take(256)).append('.').append(frame.methodName.take(128))
            append('(').append(frame.fileName?.take(128) ?: "unknown").append(':').append(frame.lineNumber).append(')')
        }
        if (frames.size > 16) append("\n ...")
        failure = current.cause
    }
}

/**
 * 将外部或模型生成的标识约束为低基数、单行的日志 token。
 */
internal fun String?.toSafeLogToken(maxLength: Int = 64): String {
    require(maxLength > 0) { "maxLength 必须大于 0" }
    val value = this ?: return UNKNOWN_LOG_TOKEN
    if (value.isEmpty() || value.length > maxLength) return UNKNOWN_LOG_TOKEN
    return value.takeIf { token ->
        token.all { character ->
            character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character == '.' ||
                character == '_' ||
                character == '-'
        }
    } ?: UNKNOWN_LOG_TOKEN
}
