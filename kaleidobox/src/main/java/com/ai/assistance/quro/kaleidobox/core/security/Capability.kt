package com.ai.assistance.quro.kaleidobox.core.security

import com.ai.assistance.quro.kaleidobox.core.KaleidoException
import com.ai.assistance.quro.kaleidobox.core.model.KValue

/**
 * 能力词表（Capability Lexicon）。
 *
 * 这是 Kaleido 与 ToolPkg 最大的治理差异：ToolPkg 的插件拿到 JS 引擎后基本等于拿到宿主全部权限；
 * Kaleido 把每一项宿主能力都变成一个**带作用域的、可在安装期授予/拒绝、运行期逐次门控**的令牌。
 *
 * 命名规范：<domain>.<action>[:<scope>]
 *   fs.read:scoped        只能读本包私有目录 + 用户显式授权的 URI
 *   net.http:host=a.com   只能访问 a.com
 *   ui.toolbox            可在工具箱渲染自己的页面
 */
object CapabilityLexicon {

    val FS = listOf(
        "fs.read:scoped", "fs.read:media", "fs.read:all",
        "fs.write:scoped", "fs.write:media", "fs.write:all",
        "fs.delete:scoped", "fs.watch"
    )
    val NET = listOf(
        "net.http", "net.websocket", "net.dns", "net.mcp", "net.download"
    )
    val UI = listOf(
        "ui.render", "ui.toolbox", "ui.settings", "ui.sidebar",
        "ui.chat-bubble", "ui.widget", "ui.overlay", "ui.input-menu",
        "ui.toast", "ui.clipboard", "ui.notification"
    )
    val AI = listOf(
        "ai.chat", "ai.available", "ai.model.invoke", "ai.provider.register", "ai.embedding",
        "ai.prompt.read", "ai.prompt.compose", "ai.prompt.hook",
        "ai.history.read", "ai.tool.register", "ai.tool.intercept"
    )
    val SYS = listOf(
        "sys.now", "sys.log", "sys.clipboard", "sys.vibrate", "sys.intent",
        "sys.settings.read", "sys.settings.write", "sys.package.info", "sys.device.info",
        "sys.accessibility", "sys.overlay.window", "sys.shizuku", "sys.root"
    )
    val IPC = listOf(
        "ipc.local", "ipc.mcp", "ipc.broadcast"
    )
    val PROC = listOf(
        "proc.exec", "proc.shell", "proc.spawn", "proc.termux",
        "app.term.run"
    )
    val MEDIA = listOf(
        "media.camera", "media.microphone", "media.screen-capture", "media.tts", "media.stt"
    )
    val DATA = listOf(
        "data.kv", "data.sqlite", "data.memory.read", "data.memory.write",
        "data.contacts", "data.location", "data.calendar",
        "worldbook.entries"
    )
    val PKG = listOf(
        "pkg.register", "pkg.discover", "pkg.install", "pkg.invoke:any"
    )
    val APP = listOf(
        "app.apk.list", "app.apk.info", "app.apk.pull", "app.apk.dex"
    )

    val all: Set<String> = (FS + NET + UI + AI + SYS + IPC + PROC + MEDIA + DATA + PKG + APP).toSet()

    /** 危险能力：安装时必须二次确认，且默认拒绝。 */
    val dangerous: Set<String> = setOf(
        "fs.read:all", "fs.write:all", "proc.exec", "proc.shell", "proc.spawn",
        "sys.root", "sys.shizuku", "sys.accessibility",
        "data.contacts", "data.location", "data.calendar",
        "media.camera", "media.microphone", "media.screen-capture",
        "net.http", "ai.prompt.read", "ai.history.read", "pkg.install", "pkg.invoke:any"
    )

    fun isDangerous(c: String) = c in dangerous

    /** 作用域解析：net.http:host=a.com → (net.http, host=a.com) */
    fun split(c: String): Pair<String, String?> {
        val i = c.indexOf(':')
        return if (i < 0) c to null else c.substring(0, i) to c.substring(i + 1)
    }
}

/** 一次能力授予记录。 */
data class Grant(
    val capability: String,
    val granted: Boolean,
    val grantedAt: Long = System.currentTimeMillis(),
    val scopeOverride: String? = null,
    /** 用户是否勾选了"始终允许"，用于抑制重复弹窗。 */
    val permanent: Boolean = false,
)

/**
 * 能力门控。每次宿主能力调用都要过这一关。
 *
 * 与"安装时一次性授权"不同：Kaleido 支持 **运行时动态授权**（Ask-Runtime），
 * 未授予时返回一个 E_PERMISSION 错误 + 一个"请求授权"句柄，宿主可弹窗让用户当场放行。
 */
interface PermissionGate {
    fun isGranted(pkgId: String, capability: String): Boolean
    fun require(pkgId: String, capability: String)
    fun grant(pkgId: String, capability: String, permanent: Boolean = true)
    fun revoke(pkgId: String, capability: String)
    fun grantsOf(pkgId: String): Map<String, Grant>
    /** 审计流：谁、什么时候、用了哪个能力、参数摘要。 */
    fun audit(pkgId: String, capability: String, argsDigest: String)
}

class InMemoryPermissionGate(
    private val auditor: ((String, String, String) -> Unit)? = null
) : PermissionGate {

    private val store = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Grant>>()
    private val auditLog = java.util.Collections.synchronizedList(mutableListOf<AuditEntry>())

    data class AuditEntry(val ts: Long, val pkgId: String, val capability: String, val argsDigest: String)

    override fun isGranted(pkgId: String, capability: String): Boolean {
        val base = CapabilityLexicon.split(capability).first
        return store[pkgId]?.let { m ->
            m[capability]?.granted == true || m[base]?.granted == true
        } ?: false
    }

    override fun require(pkgId: String, capability: String) {
        if (!isGranted(pkgId, capability)) throw com.ai.assistance.quro.kaleidobox.core.KaleidoException.Permission(capability, pkgId)
    }

    override fun grant(pkgId: String, capability: String, permanent: Boolean) {
        store.getOrPut(pkgId) { java.util.concurrent.ConcurrentHashMap() }[capability] =
            Grant(capability, true, permanent = permanent)
    }

    override fun revoke(pkgId: String, capability: String) {
        store[pkgId]?.remove(capability)
    }

    override fun grantsOf(pkgId: String): Map<String, Grant> = store[pkgId]?.toMap() ?: emptyMap()

    override fun audit(pkgId: String, capability: String, argsDigest: String) {
        auditLog += AuditEntry(System.currentTimeMillis(), pkgId, capability, argsDigest)
        auditor?.invoke(pkgId, capability, argsDigest)
    }

    fun auditTrail(pkgId: String? = null): List<AuditEntry> =
        auditLog.filter { pkgId == null || it.pkgId == pkgId }
}

/**
 * 配额：内存 / CPU 时间 / 磁盘 / 网络字节数 / 调用频率。
 * 超限时抛 [com.ai.assistance.quro.kaleidobox.core.KaleidoException.Quota]，由运行时转成结构化错误回给插件。
 */
class Quota(
    val memoryMb: Int,
    val cpuMsPerCall: Int,
    val diskMb: Int,
    val maxConcurrentCalls: Int = 4,
    val maxCallsPerMinute: Int = 600,
) {
    private val sem = java.util.concurrent.Semaphore(maxConcurrentCalls)
    private val window = java.util.Collections.synchronizedList(mutableListOf<Long>())

    fun enter() {
        if (!sem.tryAcquire()) throw com.ai.assistance.quro.kaleidobox.core.KaleidoException.Quota("concurrency", "并发调用已达上限 $maxConcurrentCalls")
        val now = System.currentTimeMillis()
        synchronized(window) {
            window.removeIf { now - it > 60_000 }
            if (window.size >= maxCallsPerMinute) {
                sem.release()
                throw com.ai.assistance.quro.kaleidobox.core.KaleidoException.Quota("rate", "每分钟调用超上限 $maxCallsPerMinute")
            }
            window += now
        }
    }

    fun exit() = sem.release()

    fun checkMemory(usedMb: Int) {
        if (usedMb > memoryMb) throw com.ai.assistance.quro.kaleidobox.core.KaleidoException.Quota("memory", "$usedMb MB > $memoryMb MB")
    }

    fun checkDisk(usedMb: Int) {
        if (usedMb > diskMb) throw com.ai.assistance.quro.kaleidobox.core.KaleidoException.Quota("disk", "$usedMb MB > $diskMb MB")
    }

    companion object {
        fun from(spec: com.ai.assistance.quro.kaleidobox.core.model.SandboxPolicySpec) = Quota(
            memoryMb = spec.memoryMb,
            cpuMsPerCall = spec.cpuMsPerCall,
            diskMb = spec.diskMb,
            maxConcurrentCalls = 4,
            maxCallsPerMinute = 600,
        )
    }
}

/** 把 KValue 参数压成一个短摘要用于审计（不落原文，避免日志里出现敏感内容）。 */
fun digestArgs(args: List<KValue>): String {
    val sb = StringBuilder()
    args.forEach { v ->
        sb.append(
            when (v) {
                is KValue.Str -> "s${v.value.length}"
                is KValue.I64 -> "i"
                is KValue.F64 -> "n"
                is KValue.Bool -> "b"
                is KValue.Bytes -> "by${v.value.size}"
                is KValue.Arr -> "a${v.value.size}"
                is KValue.Obj -> "o${v.value.size}"
                is KValue.Handle -> "h:${v.kind}"
                is KValue.Null -> "z"
                is KValue.Err -> "e:${v.code}"
            }
        ).append(',')
    }
    return sb.toString()
}
