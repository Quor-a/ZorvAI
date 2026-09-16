package com.ai.assistance.quro.kaleidobox.core.eco

import com.ai.assistance.quro.kaleidobox.core.KaleidoException
import com.ai.assistance.quro.kaleidobox.core.model.DepsSpec
import com.ai.assistance.quro.kaleidobox.core.model.SemVer
import java.io.File

/**
 * 第三方生态依赖解析器。
 *
 * ToolPkg 的插件是完全自包含的（所有代码都得塞进一个 .js），
 * 结果是没人愿意写复杂插件 —— 因为没法用 lodash、没法用 numpy。
 *
 * Kaleido 把"依赖"变成一等公民：包声明 npm / pypi / luarocks / mcp 依赖，
 * 宿主在安装期解析到该包的**私有目录**（不是全局），做到：
 *   - 版本隔离：A 包用 lodash@4，B 包用 lodash@3，互不干扰
 *   - 可审计：安装前就能列出"这个包要装 12 个 npm 包、访问网络吗"
 *   - 可离线：解析结果可固化成 lock 文件随包分发
 */

data class ResolvedDep(
    val ecosystem: String,
    val name: String,
    val version: String,
    val installPath: String,
    /** 传递依赖总数，用于给用户展示"这个包有多大"。 */
    val transitiveCount: Int = 0,
)

data class ResolvePlan(
    val ecosystem: String,
    val toInstall: List<ResolvedDep>,
    val alreadySatisfied: List<String>,
    val estimatedBytes: Long = 0,
) {
    val isEmpty get() = toInstall.isEmpty()
    fun render() = buildString {
        appendLine("[$ecosystem] 待安装 ${toInstall.size}，已满足 ${alreadySatisfied.size}")
        toInstall.forEach { appendLine("  + ${it.name}@${it.version} → ${it.installPath}") }
    }
}

/** 解析结果锁文件模型（可随包分发，实现离线安装）。 */
data class LockFile(
    val pkgId: String,
    val pkgVersion: String,
    val resolved: List<ResolvedDep>,
    val generatedAt: Long = System.currentTimeMillis(),
)

interface EcosystemResolver {
    val ecosystem: String
    fun available(): Boolean
    /** 计算需要装什么（不实际装）。 */
    fun plan(deps: DepsSpec, targetDir: File, existingLock: LockFile?): ResolvePlan
    /** 执行安装。 */
    fun install(plan: ResolvePlan, targetDir: File, onProgress: (String) -> Unit = {}): List<ResolvedDep>
}

/** npm：调用宿主内置 Node 的 npm，装到包私有 node_modules。 */
class NpmResolver(
    private val npmCommand: List<String> = listOf("npm"),
    private val registry: String? = null,
) : EcosystemResolver {

    override val ecosystem = "npm"

    override fun available(): Boolean = runCatching {
        val p = ProcessBuilder(npmCommand + listOf("--version")).start()
        p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    override fun plan(deps: DepsSpec, targetDir: File, existingLock: LockFile?): ResolvePlan {
        val satisfied = mutableListOf<String>()
        val toInstall = mutableListOf<ResolvedDep>()
        val lockIndex = existingLock?.resolved?.associateBy { "${it.ecosystem}:${it.name}" } ?: emptyMap()

        deps.npm.forEach { (name, range) ->
            val lockKey = "npm:$name"
            val locked = lockIndex[lockKey]
            val manifestFile = File(targetDir, "node_modules/$name/package.json")
            when {
                locked != null -> satisfied += "$name@${locked.version} (lock)"
                manifestFile.exists() -> {
                    val v = readVersion(manifestFile)
                    if (versionSatisfies(v, range)) satisfied += "$name@$v"
                    else toInstall += ResolvedDep("npm", name, range, "node_modules/$name")
                }
                else -> toInstall += ResolvedDep("npm", name, range, "node_modules/$name")
            }
        }
        return ResolvePlan("npm", toInstall, satisfied)
    }

    override fun install(plan: ResolvePlan, targetDir: File, onProgress: (String) -> Unit): List<ResolvedDep> {
        if (plan.isEmpty) return emptyList()
        targetDir.mkdirs()
        // 生成一次性 package.json，避免污染包根目录
        val pkgJson = File(targetDir, "package.json")
        if (!pkgJson.exists()) {
            pkgJson.writeText("""{"name":"kaleidobox-pkg","private":true,"dependencies":{}}""")
        }
        val spec = plan.toInstall.joinToString(" ") { (_, name, version) -> "$name@$version" }
        val cmd = npmCommand + listOf("install", "--no-audit", "--no-fund", "--omit=dev") +
            (registry?.let { listOf("--registry", it) } ?: emptyList()) + spec.split(" ").filter { it.isNotBlank() }
        onProgress("执行: ${cmd.joinToString(" ")}")
        val p = ProcessBuilder(cmd).directory(targetDir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        if (code != 0) throw KaleidoException.Resolve("npm install 失败($code): ${out.takeLast(600)}")
        onProgress(out.takeLast(300))
        return plan.toInstall.map { it.copy(version = readVersion(File(targetDir, "${it.installPath}/package.json")) ?: it.version) }
    }

    private fun readVersion(f: File): String? = runCatching {
        val t = f.readText()
        Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1)
    }.getOrNull()

    private fun versionSatisfies(v: String?, range: String): Boolean {
        if (v == null) return false
        return runCatching { SemVer.parse(v).satisfies(range) }.getOrDefault(false)
    }
}

/** PyPI：优先用 micropip/uv 装到私有 site-packages；退化到 pip --target。 */
class PipResolver(
    private val pipCommand: List<String> = listOf("python3", "-m", "pip"),
    private val indexUrl: String? = null,
) : EcosystemResolver {

    override val ecosystem = "pypi"

    override fun available() = runCatching {
        val p = ProcessBuilder(pipCommand + listOf("--version")).start()
        p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    override fun plan(deps: DepsSpec, targetDir: File, existingLock: LockFile?): ResolvePlan {
        val satisfied = mutableListOf<String>()
        val toInstall = mutableListOf<ResolvedDep>()
        val locked = existingLock?.resolved?.filter { it.ecosystem == "pypi" }?.associateBy { it.name } ?: emptyMap()
        deps.pypi.forEach { spec ->
            val name = spec.substringBefore("==").substringBefore(">=").substringBefore("~=").trim()
            when {
                locked.containsKey(name) -> satisfied += "$name (lock)"
                File(targetDir, "site-packages/$name").exists() -> satisfied += "$name"
                else -> toInstall += ResolvedDep("pypi", name, spec, "site-packages/$name")
            }
        }
        return ResolvePlan("pypi", toInstall, satisfied)
    }

    override fun install(plan: ResolvePlan, targetDir: File, onProgress: (String) -> Unit): List<ResolvedDep> {
        if (plan.isEmpty) return emptyList()
        val site = File(targetDir, "site-packages").apply { mkdirs() }
        val cmd = pipCommand + listOf("install", "--target", site.absolutePath, "--no-cache-dir") +
            (indexUrl?.let { listOf("-i", it) } ?: emptyList()) +
            plan.toInstall.map { it.version }
        onProgress("执行: ${cmd.joinToString(" ")}")
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw KaleidoException.Resolve("pip install 失败: ${out.takeLast(600)}")
        return plan.toInstall
    }
}

/** LuaRocks。 */
class LuaRocksResolver(private val cmd: List<String> = listOf("luarocks")) : EcosystemResolver {
    override val ecosystem = "luarocks"
    override fun available() = runCatching {
        ProcessBuilder(cmd + listOf("--version")).start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
    }.getOrDefault(false)

    override fun plan(deps: DepsSpec, targetDir: File, existingLock: LockFile?): ResolvePlan {
        val satisfied = mutableListOf<String>()
        val toInstall = deps.luarocks.filter { spec ->
            val name = spec.substringBefore(" ")
            if (File(targetDir, "lua_modules/share/lua/5.1/$name.lua").exists()) {
                satisfied += spec; false
            } else true
        }.map { spec -> ResolvedDep("luarocks", spec.substringBefore(" "), spec, "lua_modules") }
        return ResolvePlan("luarocks", toInstall, satisfied)
    }

    override fun install(plan: ResolvePlan, targetDir: File, onProgress: (String) -> Unit): List<ResolvedDep> {
        if (plan.isEmpty) return emptyList()
        val tree = File(targetDir, "lua_modules").apply { mkdirs() }
        plan.toInstall.forEach { d ->
            val p = ProcessBuilder(cmd + listOf("install", d.version, "--tree", tree.absolutePath))
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw KaleidoException.Resolve("luarocks install 失败: ${out.takeLast(400)}")
            onProgress("已安装 ${d.name}")
        }
        return plan.toInstall
    }
}

/**
 * 依赖管理总入口：安装前先 plan（给用户看），确认后再 install。
 * 这个"先计划后执行"的两段式，是移动端插件生态能安全引入第三方依赖的前提。
 */
class DependencyManager(
    private val targetDirOf: (String) -> File,
    private val resolvers: List<EcosystemResolver> = emptyList(),
    private val lockStore: (String) -> LockFile? = { null },
) {
    fun planAll(pkgId: String, deps: DepsSpec): List<ResolvePlan> =
        resolvers.map { it.plan(deps, targetDirOf(pkgId), lockStore(pkgId)) }

    fun installAll(pkgId: String, deps: DepsSpec, onProgress: (String) -> Unit = {}): List<ResolvedDep> {
        val out = mutableListOf<ResolvedDep>()
        resolvers.forEach { r ->
            val plan = r.plan(deps, targetDirOf(pkgId), lockStore(pkgId))
            if (!plan.isEmpty) out += r.install(plan, targetDirOf(pkgId), onProgress)
        }
        return out
    }

    fun availableEcosystems() = resolvers.filter { it.available() }.map { it.ecosystem }
}
