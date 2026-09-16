package com.ai.assistance.quro.kaleidobox.core.model

import com.ai.assistance.quro.kaleidobox.core.KaleidoException

/** Kaleido 清单的强类型模型。反序列化后须经 [com.ai.assistance.quro.kaleidobox.core.manifest.ManifestValidator] 校验。 */

data class KaleidoManifest(
    val schema: Int,
    val id: String,
    val version: String,
    val name: Map<String, String>,
    val description: Map<String, String> = emptyMap(),
    val authors: List<String> = emptyList(),
    val license: String? = null,
    val homepage: String? = null,
    val keywords: List<String> = emptyList(),
    val minHost: String? = null,
    val runtime: List<RuntimeUnit>,
    val units: List<UnitSpec> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val sandbox: SandboxPolicySpec = SandboxPolicySpec(),
    val ui: List<UiSurfaceSpec> = emptyList(),
    val hooks: List<HookSpec> = emptyList(),
    val deps: DepsSpec = DepsSpec(),
    val resources: List<ResourceSpec> = emptyList(),
    val provides: ProvidesSpec = ProvidesSpec(),
    val compat: CompatSpec = CompatSpec(),
) {
    /** runtime:fn → (runtimeId, fn)。target 的规范形式。 */
    fun resolveTarget(target: String): Pair<String, String> {
        val idx = target.indexOf(':')
        require(idx > 0) { "非法 target: '$target'，应为 runtimeId:fnName" }
        return target.substring(0, idx) to target.substring(idx + 1)
    }

    fun runtimeById(id: String): RuntimeUnit =
        runtime.firstOrNull { it.id == id }
            ?: throw KaleidoException.Manifest("未声明的 runtime id: $id")

    fun unitByName(name: String): UnitSpec? = units.firstOrNull { it.name == name }
}

data class RuntimeUnit(
    val id: String,
    val lang: Lang,
    val engine: EngineKind,
    val entry: String,
    val sourceMap: Boolean = true,
    val shared: Boolean = false,
    val warm: Boolean = false,
    val initTimeoutMs: Int = 5000,
    val exports: List<String> = emptyList(),
)

enum class Lang(vararg val aliases: String) {
    JAVASCRIPT("js"), TYPESCRIPT("ts"), PYTHON("py"), LUA, WASM, KOTLIN("kt"), JAVA, SHELL("sh");

    companion object {
        fun parse(s: String): Lang = values().firstOrNull {
            it.name.equals(s, true) || it.aliases.any { a -> a.equals(s, true) }
        } ?: throw KaleidoException.Manifest("不支持的语言: $s")
    }
}

enum class EngineKind {
    QUICKJS, LUAJIT, CPYTHON, WASM, JVM_DEX, NODE, AUTO;

    companion object {
        fun parse(s: String): EngineKind = values().firstOrNull {
            it.name.equals(s.replace('-', '_'), true)
        } ?: throw KaleidoException.Manifest("不支持的引擎: $s")

        /** 语言 → 默认引擎。AUTO 时的解析结果。 */
        fun defaultFor(lang: Lang): EngineKind = when (lang) {
            Lang.JAVASCRIPT, Lang.TYPESCRIPT -> QUICKJS
            Lang.PYTHON -> CPYTHON
            Lang.LUA -> LUAJIT
            Lang.WASM -> WASM
            Lang.KOTLIN, Lang.JAVA -> JVM_DEX
            Lang.SHELL -> NODE
        }
    }
}

data class UnitSpec(
    val name: String,
    val runtime: String,
    val target: String,
    val title: Map<String, String> = emptyMap(),
    val description: String = "",
    val params: Map<String, Any?>? = null,
    val returns: String? = null,
    val capabilities: List<String> = emptyList(),
    val stream: Boolean = false,
    val timeoutMs: Int = 30_000,
    val idempotent: Boolean = false,
    val concurrency: Int = 1,
    val cacheTtlMs: Int = 0,
)

data class SandboxPolicySpec(
    val level: SandboxLevel = SandboxLevel.IN_PROCESS,
    val memoryMb: Int = 128,
    val cpuMsPerCall: Int = 5000,
    val diskMb: Int = 64,
    val netEgress: NetEgress = NetEgress.DENY,
    val allowHosts: List<String> = emptyList(),
    val allowExec: Boolean = false,
    val seccomp: Boolean = true,
)

enum class SandboxLevel { IN_PROCESS, ISOLATED_PROCESS, WASM_SANDBOX, REMOTE }
enum class NetEgress { DENY, ALLOWLIST, ALLOW }

data class UiSurfaceSpec(
    val id: String,
    val surface: UiSurface,
    val render: String,
    val onAction: String? = null,
    val title: Map<String, String> = emptyMap(),
    val icon: String? = null,
    val hotReload: Boolean = true,
    val priority: Int = 100,
)

enum class UiSurface {
    TOOLBOX, SETTINGS, SIDEBAR, CHAT_BUBBLE, WIDGET, OVERLAY, INPUT_MENU;

    companion object {
        fun parse(s: String) = values().firstOrNull {
            it.name.equals(s.replace('-', '_'), true)
        } ?: throw KaleidoException.Manifest("未知的 UI surface: $s")
    }
}

data class HookSpec(
    val point: String,
    val target: String,
    val priority: Int = 100,
    val mode: HookMode = HookMode.MODIFY,
    val async: Boolean = false,
    val timeoutMs: Int = 2000,
)

enum class HookMode { OBSERVE, MODIFY, VETO }

data class DepsSpec(
    val npm: Map<String, String> = emptyMap(),
    val pypi: List<String> = emptyList(),
    val luarocks: List<String> = emptyList(),
    val mcp: List<McpDep> = emptyList(),
    val kaleido: Map<String, String> = emptyMap(),
)

data class McpDep(
    val name: String,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val url: String? = null,
    val transport: String = "stdio",
)

data class ResourceSpec(
    val key: String,
    val path: String,
    val mime: String,
    val integrity: String? = null,
)

data class ProvidesSpec(
    val aiProvider: List<String> = emptyList(),
    val mcpServer: McpServerSpec? = null,
    val fileProvider: List<String> = emptyList(),
)

data class McpServerSpec(
    val transport: String = "inproc",
    val tools: List<String> = emptyList(),
)

data class CompatSpec(
    val toolpkg: String? = null,
    val mcp: Map<String, Any?>? = null,
    val vscode: Map<String, Any?>? = null,
)

/** 语义化版本，够用即可（不引入外部依赖）。 */
data class SemVer(val major: Int, val minor: Int, val patch: Int, val pre: String? = null) :
    Comparable<SemVer> {

    companion object {
        private val RE = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?$")
        fun parse(s: String): SemVer = RE.matchEntire(s)?.let {
            SemVer(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt(),
                it.groupValues[4].ifEmpty { null })
        } ?: throw KaleidoException.Manifest("非法语义化版本: $s")
    }

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        return when {
            pre == null && other.pre == null -> 0
            pre == null -> 1
            other.pre == null -> -1
            else -> pre.compareTo(other.pre)
        }
    }

    override fun toString() = "$major.$minor.$patch${pre?.let { "-$it" } ?: ""}"

    /** 是否满足 caret / tilde / 精确 区间描述。 */
    fun satisfies(range: String): Boolean {
        val r = range.trim()
        if (r == "*" || r.isEmpty()) return true
        return when {
            r.startsWith("^") -> {
                val v = parse(r.drop(1))
                if (v.major == 0) this.major == 0 && this.minor == v.minor && this >= v
                else this.major == v.major && this >= v
            }
            r.startsWith("~") -> {
                val v = parse(r.drop(1))
                this.major == v.major && this.minor == v.minor && this >= v
            }
            r.startsWith(">=") -> this >= parse(r.drop(2))
            r.startsWith(">") -> this > parse(r.drop(1))
            r.startsWith("<=") -> this <= parse(r.drop(2))
            r.startsWith("<") -> this < parse(r.drop(1))
            else -> this == parse(r)
        }
    }
}
