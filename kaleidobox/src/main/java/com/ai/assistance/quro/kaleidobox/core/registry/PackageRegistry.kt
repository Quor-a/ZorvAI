package com.ai.assistance.quro.kaleidobox.core.registry

import com.ai.assistance.quro.kaleidobox.core.KaleidoException
import com.ai.assistance.quro.kaleidobox.core.engine.HostBridge
import com.ai.assistance.quro.kaleidobox.core.engine.InvokeContext
import com.ai.assistance.quro.kaleidobox.core.model.*
import com.ai.assistance.quro.kaleidobox.core.security.Grant

/**
 * 已安装包记录。
 *
 * 与 ToolPkg 的"一个包一个目录"不同，Kaleido 支持：
 *   - 多版本并存（同一个 id 装多个 version，靠 [activeVersion] 切换）—— 灰度/回滚的基础；
 *   - 每个 runtime 单元独立会话，崩溃单点隔离；
 *   - 依赖图（[dependsOn] / [dependedBy]）在卸载时做安全检测。
 */
data class PackageRecord(
    val manifest: KaleidoManifest,
    val installPath: String,
    val installedAt: Long = System.currentTimeMillis(),
    var enabled: Boolean = true,
    var active: Boolean = true,
    val source: InstallSource = InstallSource.SIDELOAD,
    val signatureValid: Boolean = true,
    val integrity: String? = null,
) {
    val id: String get() = manifest.id
    val version: SemVer get() = SemVer.parse(manifest.version)
}

enum class InstallSource { SIDELOAD, MARKET, BUILTIN, DEV_HOTRELOAD, MIGRATED_TOOLPKG }

/** 包内容读取抽象。Android 侧实现为 APK assets / 私有目录；JVM 侧实现为文件夹。 */
interface PackageSource {
    /** 读取包内文件（相对路径）。 */
    fun read(path: String): ByteArray?
    fun exists(path: String): Boolean
    /** 包内所有文件清单（用于完整性校验与增量更新）。 */
    fun list(): List<String>
    /** 计算 sha256，用于内容寻址与篡改检测。 */
    fun digest(path: String): String
}

class DirectoryPackageSource(private val root: java.io.File) : PackageSource {
    override fun read(path: String): ByteArray? = root.resolve(path).takeIf { it.isFile }?.readBytes()
    override fun exists(path: String) = root.resolve(path).exists()
    override fun list(): List<String> = root.walkTopDown().filter { it.isFile }
        .map { it.relativeTo(root).path.replace('\\', '/') }.toList()

    override fun digest(path: String): String {
        val b = read(path) ?: return ""
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(b).joinToString("") { "%02x".format(it) }
    }
}

/** 内存包源 —— 单测与热重载用。 */
class MemoryPackageSource(private val files: Map<String, ByteArray>) : PackageSource {
    override fun read(path: String) = files[path]
    override fun exists(path: String) = files.containsKey(path)
    override fun list() = files.keys.toList()
    override fun digest(path: String) = files[path]?.let {
        java.security.MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { "%02x".format(it) }
    } ?: ""
}

/**
 * 包注册中心：已安装包的索引与依赖图。
 * 纯数据 + 纯逻辑，不碰文件系统与引擎，方便单测。
 */
class PackageRegistry {

    private val byId = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, PackageRecord>>()
    private val sources = java.util.concurrent.ConcurrentHashMap<String, PackageSource>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(RegistryEvent) -> Unit>()

    sealed class RegistryEvent {
        data class Installed(val record: PackageRecord) : RegistryEvent()
        data class Removed(val pkgId: String, val version: String) : RegistryEvent()
        data class EnabledChanged(val pkgId: String, val enabled: Boolean) : RegistryEvent()
    }

    fun onEvent(l: (RegistryEvent) -> Unit) = listeners.add(l)
    private fun fire(e: RegistryEvent) = listeners.forEach { runCatching { it(e) } }

    fun install(record: PackageRecord, source: PackageSource) {
        val versions = byId.getOrPut(record.id) { java.util.concurrent.ConcurrentHashMap() }
        if (versions.containsKey(record.version.toString())) {
            // 同版本重装：视为覆盖更新，先卸旧的会话（由 Runtime 负责）
            fire(RegistryEvent.Removed(record.id, record.version.toString()))
        }
        versions[record.version.toString()] = record
        sources[key(record.id, record.version.toString())] = source
        fire(RegistryEvent.Installed(record))
    }

    fun remove(pkgId: String, version: String) {
        byId[pkgId]?.remove(version)?.let {
            sources.remove(key(pkgId, version))
            fire(RegistryEvent.Removed(pkgId, version))
        }
        if (byId[pkgId]?.isEmpty() == true) byId.remove(pkgId)
    }

    fun get(pkgId: String, version: String? = null): PackageRecord? {
        val versions = byId[pkgId] ?: return null
        return version?.let { versions[it] } ?: versions.values.maxByOrNull { it.version }
    }

    fun source(pkgId: String, version: String? = null): PackageSource? {
        val rec = get(pkgId, version) ?: return null
        return sources[key(pkgId, rec.version.toString())]
    }

    fun versionsOf(pkgId: String): List<PackageRecord> =
        byId[pkgId]?.values?.sortedByDescending { it.version } ?: emptyList()

    fun all(): List<PackageRecord> = byId.values.flatMap { it.values }

    fun allEnabled() = all().filter { it.enabled }

    fun setEnabled(pkgId: String, on: Boolean) {
        byId[pkgId]?.values?.forEach { it.enabled = on }
        fire(RegistryEvent.EnabledChanged(pkgId, on))
    }

    /** 所有已注册 unit 的扁平索引：unitName → (pkgId, version)。冲突时给出明确错误。 */
    fun unitIndex(): Map<String, Pair<String, String>> {
        val out = mutableMapOf<String, Pair<String, String>>()
        val conflicts = mutableListOf<String>()
        allEnabled().sortedBy { it.id }.forEach { rec ->
            rec.manifest.units.forEach { u ->
                val prev = out.put(u.name, rec.id to rec.version.toString())
                if (prev != null && prev.first != rec.id) {
                    conflicts += "unit 名冲突: '${u.name}' (${prev.first} vs ${rec.id})"
                }
            }
        }
        if (conflicts.isNotEmpty()) throw KaleidoException.Resolve(conflicts.joinToString("; "))
        return out
    }

    /** 依赖图检测：卸载 A 前，确认没有别的包依赖它。 */
    fun dependents(pkgId: String): List<String> = allEnabled().filter { rec ->
        rec.manifest.deps.kaleido.containsKey(pkgId)
    }.map { it.id }

    /** 依赖是否都已满足（用于安装期阻断）。 */
    fun missingDependencies(rec: PackageRecord): List<String> = rec.manifest.deps.kaleido.filter { (depId, range) ->
        val installed = get(depId) ?: return@filter true
        !installed.version.satisfies(range)
    }.map { (id, range) -> "$id@$range" }

    fun stats() = mapOf(
        "packages" to byId.size,
        "versions" to all().size,
        "units" to runCatching { unitIndex().size }.getOrDefault(0),
    )

    private fun key(id: String, v: String) = "$id@$v"
}

/**
 * 跨引擎能力解析：把 "pkgId:unitName" 或 "unitName" 解析到具体包。
 * 这是"跨包互相调用"的基础，ToolPkg 里没有对应能力。
 */
class UnitResolver(private val registry: PackageRegistry) {

    sealed class Ref {
        data class Local(val pkgId: String, val unit: String) : Ref()
        data class Global(val unit: String) : Ref()
        data class Mcp(val server: String, val tool: String) : Ref()
    }

    fun parse(s: String): Ref = when {
        s.startsWith("mcp://") -> {
            val rest = s.removePrefix("mcp://")
            val i = rest.indexOf('/')
            Ref.Mcp(rest.substring(0, i), rest.substring(i + 1))
        }
        s.contains(':') -> {
            val (a, b) = s.split(':', limit = 2)
            Ref.Local(a, b)
        }
        else -> Ref.Global(s)
    }

    fun resolve(ref: Ref, callerPkg: String?): Pair<PackageRecord, UnitSpec> = when (ref) {
        is Ref.Local -> {
            val rec = registry.get(ref.pkgId)
                ?: throw KaleidoException.Resolve("未安装的包: ${ref.pkgId}")
            val u = rec.manifest.unitByName(ref.unit)
                ?: throw KaleidoException.Resolve("包 ${ref.pkgId} 没有 unit: ${ref.unit}")
            rec to u
        }
        is Ref.Global -> {
            val idx = registry.unitIndex()
            val (pkgId, _) = idx[ref.unit]
                ?: throw KaleidoException.Resolve("没有包提供 unit: ${ref.unit}")
            val rec = registry.get(pkgId)!!
            rec to rec.manifest.unitByName(ref.unit)!!
        }
        is Ref.Mcp -> throw KaleidoException.Resolve("MCP 工具请走 McpBridge: ${ref.server}/${ref.tool}")
    }
}

/** 调用结果缓存（unit 声明 idempotent + cacheTtl 时启用）。 */
class UnitCache {
    private data class Entry(val value: KValue, val expireAt: Long)
    private val store = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun get(key: String): KValue? = store[key]?.takeIf { it.expireAt > System.currentTimeMillis() }?.value
    fun put(key: String, v: KValue, ttlMs: Int) {
        if (ttlMs <= 0) return
        store[key] = Entry(v, System.currentTimeMillis() + ttlMs)
    }

    fun invalidate(pkgId: String) = store.keys.removeIf { it.startsWith("$pkgId:") }
    fun clear() = store.clear()
    fun size() = store.size
}
