package com.ai.assistance.quro.core.mika.workspace

/** 沙箱实现报给工具层的已知错误;[code] 原样成为工具结果的 error(如 sandbox_network_isolation_unavailable)。 */
class SandboxError(val code: String, message: String, val recoverable: Boolean = false) : Exception(message)
