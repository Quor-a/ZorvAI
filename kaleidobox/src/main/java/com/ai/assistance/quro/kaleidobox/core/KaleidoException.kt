package com.ai.assistance.quro.kaleidobox.core

/** Kaleido 统一异常族。跨引擎边界时会被转成 [com.ai.assistance.quro.kaleidobox.core.model.KValue.Err]。 */
sealed class KaleidoException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {

    class Manifest(message: String) : KaleidoException(message)
    class Engine(message: String, cause: Throwable? = null) : KaleidoException(message, cause)
    class Permission(val capability: String, val pkgId: String) :
        KaleidoException("包 $pkgId 未获得能力: $capability")

    class Quota(kind: String, detail: String) : KaleidoException("配额超限 [$kind]: $detail")
    class Timeout(what: String, ms: Int) : KaleidoException("$what 超时 (${ms}ms)")
    class Unit(unitName: String, message: String) : KaleidoException("unit '$unitName' 执行失败: $message")
    class Resolve(message: String) : KaleidoException(message)
    class Install(pkgId: String, message: String) : KaleidoException("安装 $pkgId 失败: $message")

    /** 转成跨界错误值，保证任何语言都能读到结构化错误。 */
    fun toKValue(): com.ai.assistance.quro.kaleidobox.core.model.KValue = when (this) {
        is Permission -> com.ai.assistance.quro.kaleidobox.core.model.KValue.Err(
            "E_PERMISSION", message ?: "", com.ai.assistance.quro.kaleidobox.core.model.KValue.obj("capability" to capability, "pkg" to pkgId)
        )
        is Quota -> com.ai.assistance.quro.kaleidobox.core.model.KValue.Err("E_QUOTA", message ?: "")
        is Timeout -> com.ai.assistance.quro.kaleidobox.core.model.KValue.Err("E_TIMEOUT", message ?: "")
        is Unit -> com.ai.assistance.quro.kaleidobox.core.model.KValue.Err("E_UNIT", message ?: "")
        is Manifest -> com.ai.assistance.quro.kaleidobox.core.model.KValue.Err("E_MANIFEST", message ?: "")
        is Engine -> com.ai.assistance.quro.kaleidobox.core.model.KValue.Err("E_ENGINE", message ?: "")
        is Resolve -> com.ai.assistance.quro.kaleidobox.core.model.KValue.Err("E_RESOLVE", message ?: "")
        is Install -> com.ai.assistance.quro.kaleidobox.core.model.KValue.Err("E_INSTALL", message ?: "")
    }
}
