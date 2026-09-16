package com.ai.assistance.quro.kaleidobox.core.manifest

import com.ai.assistance.quro.kaleidobox.core.KaleidoException
import com.ai.assistance.quro.kaleidobox.core.hooks.HookMatrix
import com.ai.assistance.quro.kaleidobox.core.model.*
import com.ai.assistance.quro.kaleidobox.core.security.CapabilityLexicon
import com.ai.assistance.quro.kaleidobox.core.util.Json

/**
 * 清单解析 + 语义校验。
 *
 * 与 ToolPkg 只做「字段存在性检查」不同，这里做的是**全量语义校验**：
 * 引用完整性（unit.target → runtime）、能力合法性、配额合理性、依赖自洽、ID 唯一性。
 * 目标：坏包在**安装期**就失败，而不是运行到一半崩在用户面前。
 */
object ManifestParser {

    fun parse(text: String): KaleidoManifest {
        val raw = Json.parse(text) as? Map<*, *>
            ?: throw KaleidoException.Manifest("清单根节点必须是 JSON 对象")
        @Suppress("UNCHECKED_CAST")
        return fromMap(raw as Map<String, Any?>)
    }

    @Suppress("UNCHECKED_CAST")
    fun fromMap(m: Map<String, Any?>): KaleidoManifest {
        fun str(k: String) = m[k] as? String
        fun int(k: String, d: Int) = (m[k] as? Number)?.toInt() ?: d
        fun bool(k: String, d: Boolean) = m[k] as? Boolean ?: d
        fun strMap(k: String): Map<String, String> =
            (m[k] as? Map<*, *>)?.map { it.key.toString() to it.value.toString() }?.toMap() ?: emptyMap()
        fun strList(k: String): List<String> =
            (m[k] as? List<*>)?.map { it.toString() } ?: emptyList()

        require(m["schema"] != null) { throw KaleidoException.Manifest("缺少 schema 字段") }
        requireNotNull(str("id")) { throw KaleidoException.Manifest("缺少 id") }
        requireNotNull(str("version")) { throw KaleidoException.Manifest("缺少 version") }

        val runtime = (m["runtime"] as? List<*>)
            ?.map { it as Map<*, *> }
            ?.map { r ->
                @Suppress("UNCHECKED_CAST")
                val rr = r as Map<String, Any?>
                RuntimeUnit(
                    id = rr["id"] as? String ?: throw KaleidoException.Manifest("runtime 缺少 id"),
                    lang = Lang.parse(rr["lang"] as? String ?: "javascript"),
                    engine = (rr["engine"] as? String)?.let { EngineKind.parse(it) } ?: EngineKind.AUTO,
                    entry = rr["entry"] as? String ?: throw KaleidoException.Manifest("runtime 缺少 entry"),
                    sourceMap = rr["sourceMap"] as? Boolean ?: true,
                    shared = rr["shared"] as? Boolean ?: false,
                    warm = rr["warm"] as? Boolean ?: false,
                    initTimeoutMs = (rr["initTimeoutMs"] as? Number)?.toInt() ?: 5000,
                    exports = (rr["exports"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                )
            } ?: throw KaleidoException.Manifest("缺少 runtime 数组")

        val units = (m["units"] as? List<*>)?.map { u ->
            @Suppress("UNCHECKED_CAST")
            val uu = u as Map<String, Any?>
            UnitSpec(
                name = uu["name"] as? String ?: throw KaleidoException.Manifest("unit 缺少 name"),
                runtime = uu["runtime"] as? String ?: "",
                target = uu["target"] as? String ?: throw KaleidoException.Manifest("unit 缺少 target"),
                title = (uu["title"] as? Map<*, *>)?.map { it.key.toString() to it.value.toString() }?.toMap()
                    ?: emptyMap(),
                description = uu["description"] as? String ?: "",
                params = (uu["params"] as? Map<*, *>)?.let {
                    @Suppress("UNCHECKED_CAST") it as Map<String, Any?>
                },
                returns = uu["returns"] as? String,
                capabilities = (uu["capabilities"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                stream = uu["stream"] as? Boolean ?: false,
                timeoutMs = (uu["timeoutMs"] as? Number)?.toInt() ?: 30_000,
                idempotent = uu["idempotent"] as? Boolean ?: false,
                concurrency = (uu["concurrency"] as? Number)?.toInt() ?: 1,
                cacheTtlMs = (uu["cacheTtlMs"] as? Number)?.toInt() ?: 0,
            )
        } ?: emptyList()

        val sandboxRaw = m["sandbox"] as? Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val sr = sandboxRaw as? Map<String, Any?>
        val sandbox = SandboxPolicySpec(
            level = (sr?.get("level") as? String)?.let { v ->
                SandboxLevel.values().firstOrNull { it.name.equals(v.replace('-', '_'), true) }
                    ?: throw KaleidoException.Manifest("未知 sandbox.level: $v")
            } ?: SandboxLevel.IN_PROCESS,
            memoryMb = (sr?.get("memoryMb") as? Number)?.toInt() ?: 128,
            cpuMsPerCall = (sr?.get("cpuMsPerCall") as? Number)?.toInt() ?: 5000,
            diskMb = (sr?.get("diskMb") as? Number)?.toInt() ?: 64,
            netEgress = (sr?.get("netEgress") as? String)?.let { v ->
                NetEgress.values().firstOrNull { it.name.equals(v, true) }
                    ?: throw KaleidoException.Manifest("未知 netEgress: $v")
            } ?: NetEgress.DENY,
            allowHosts = (sr?.get("allowHosts") as? List<*>)?.map { it.toString() } ?: emptyList(),
            allowExec = sr?.get("allowExec") as? Boolean ?: false,
            seccomp = sr?.get("seccomp") as? Boolean ?: true,
        )

        val ui = (m["ui"] as? List<*>)?.map { x ->
            @Suppress("UNCHECKED_CAST")
            val xx = x as Map<String, Any?>
            UiSurfaceSpec(
                id = xx["id"] as? String ?: throw KaleidoException.Manifest("ui 缺少 id"),
                surface = UiSurface.parse(xx["surface"] as? String ?: "toolbox"),
                render = xx["render"] as? String ?: throw KaleidoException.Manifest("ui 缺少 render"),
                onAction = xx["onAction"] as? String,
                title = (xx["title"] as? Map<*, *>)?.map { it.key.toString() to it.value.toString() }?.toMap()
                    ?: emptyMap(),
                icon = xx["icon"] as? String,
                hotReload = xx["hotReload"] as? Boolean ?: true,
                priority = (xx["priority"] as? Number)?.toInt() ?: 100,
            )
        } ?: emptyList()

        val hooks = (m["hooks"] as? List<*>)?.map { x ->
            @Suppress("UNCHECKED_CAST")
            val xx = x as Map<String, Any?>
            HookSpec(
                point = xx["point"] as? String ?: throw KaleidoException.Manifest("hook 缺少 point"),
                target = xx["target"] as? String ?: throw KaleidoException.Manifest("hook 缺少 target"),
                priority = (xx["priority"] as? Number)?.toInt() ?: 100,
                mode = (xx["mode"] as? String)?.let { HookMode.valueOf(it.uppercase()) } ?: HookMode.MODIFY,
                async = xx["async"] as? Boolean ?: false,
                timeoutMs = (xx["timeoutMs"] as? Number)?.toInt() ?: 2000,
            )
        } ?: emptyList()

        val depsRaw = m["deps"] as? Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val dr = depsRaw as? Map<String, Any?>
        val deps = DepsSpec(
            npm = (dr?.get("npm") as? Map<*, *>)?.map { it.key.toString() to it.value.toString() }?.toMap()
                ?: emptyMap(),
            pypi = (dr?.get("pypi") as? List<*>)?.map { it.toString() } ?: emptyList(),
            luarocks = (dr?.get("luarocks") as? List<*>)?.map { it.toString() } ?: emptyList(),
            mcp = (dr?.get("mcp") as? List<*>)?.map { x ->
                @Suppress("UNCHECKED_CAST")
                val xx = x as Map<String, Any?>
                McpDep(
                    name = xx["name"] as? String ?: "",
                    command = xx["command"] as? String,
                    args = (xx["args"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                    url = xx["url"] as? String,
                    transport = xx["transport"] as? String ?: "stdio",
                )
            } ?: emptyList(),
            kaleido = (dr?.get("kaleido") as? Map<*, *>)?.map { it.key.toString() to it.value.toString() }?.toMap()
                ?: emptyMap(),
        )

        val resources = (m["resources"] as? List<*>)?.map { x ->
            @Suppress("UNCHECKED_CAST")
            val xx = x as Map<String, Any?>
            ResourceSpec(
                key = xx["key"] as? String ?: "",
                path = xx["path"] as? String ?: "",
                mime = xx["mime"] as? String ?: "application/octet-stream",
                integrity = xx["integrity"] as? String,
            )
        } ?: emptyList()

        val providesRaw = m["provides"] as? Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val pr = providesRaw as? Map<String, Any?>
        val mcpServerSpec = (pr?.get("mcpServer") as? Map<*, *>)?.let {
            @Suppress("UNCHECKED_CAST")
            val mm = it as Map<String, Any?>
            McpServerSpec(
                transport = mm["transport"] as? String ?: "inproc",
                tools = (mm["tools"] as? List<*>)?.map { x -> x.toString() } ?: emptyList()
            )
        }
        val provides = ProvidesSpec(
            aiProvider = (pr?.get("aiProvider") as? List<*>)?.map { it.toString() } ?: emptyList(),
            mcpServer = mcpServerSpec,
            fileProvider = (pr?.get("fileProvider") as? List<*>)?.map { it.toString() } ?: emptyList(),
        )

        val compatRaw = m["compat"] as? Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val cr = compatRaw as? Map<String, Any?>
        val compat = CompatSpec(
            toolpkg = cr?.get("toolpkg") as? String,
            mcp = cr?.get("mcp") as? Map<String, Any?>,
            vscode = cr?.get("vscode") as? Map<String, Any?>,
        )

        return KaleidoManifest(
            schema = (m["schema"] as? Number)?.toInt() ?: 1,
            id = str("id")!!,
            version = str("version")!!,
            name = strMap("name").ifEmpty { mapOf("en" to str("id")!!) },
            description = strMap("description"),
            authors = strList("authors"),
            license = str("license"),
            homepage = str("homepage"),
            keywords = strList("keywords"),
            minHost = str("minHost"),
            runtime = runtime,
            units = units,
            capabilities = strList("capabilities"),
            sandbox = sandbox,
            ui = ui,
            hooks = hooks,
            deps = deps,
            resources = resources,
            provides = provides,
            compat = compat,
        )
    }
}

object ManifestValidator {

    data class Issue(val level: Level, val path: String, val message: String) {
        override fun toString() = "[${level.name}] $path: $message"
    }

    enum class Level { ERROR, WARN }

    /** 宿主已注册的能力白名单；不在名单里的能力直接判错（防拼写错误静默失效）。 */
    fun validate(m: KaleidoManifest, knownCapabilities: Set<String> = CapabilityLexicon.all): List<Issue> {
        val out = mutableListOf<Issue>()

        if (m.schema != 1) out += Issue(Level.ERROR, "schema", "暂不支持的 schema 版本 ${m.schema}")
        if (!m.id.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){1,4}$")))
            out += Issue(Level.ERROR, "id", "'${m.id}' 不合规范，应为小写反域名，如 dev.kaleido.hello")
        runCatching { SemVer.parse(m.version) }.onFailure {
            out += Issue(Level.ERROR, "version", "非法语义化版本: ${m.version}")
        }

        val rtIds = mutableSetOf<String>()
        m.runtime.forEachIndexed { i, r ->
            val p = "runtime[$i]"
            if (!rtIds.add(r.id)) out += Issue(Level.ERROR, "$p.id", "runtime id 重复: ${r.id}")
            if (r.entry.startsWith("/") || r.entry.contains(".."))
                out += Issue(Level.ERROR, "$p.entry", "entry 必须是包内相对路径且不含 ..: ${r.entry}")
            if (r.initTimeoutMs < 100) out += Issue(Level.WARN, "$p.initTimeoutMs", "过小，冷启动容易失败")
            val eff = if (r.engine == EngineKind.AUTO) EngineKind.defaultFor(r.lang) else r.engine
            if (!engineSupports(eff, r.lang))
                out += Issue(Level.ERROR, "$p", "引擎 $eff 不支持语言 ${r.lang}")
        }
        if (m.runtime.isEmpty()) out += Issue(Level.ERROR, "runtime", "至少需要 1 个 runtime")

        val unitNames = mutableSetOf<String>()
        m.units.forEachIndexed { i, u ->
            val p = "units[$i]"
            if (!u.name.matches(Regex("^[a-z][a-z0-9_]{1,47}$")))
                out += Issue(Level.ERROR, "$p.name", "'${u.name}' 不合规范（小写字母开头，仅 a-z0-9_）")
            if (!unitNames.add(u.name)) out += Issue(Level.ERROR, "$p.name", "unit 名重复: ${u.name}")

            val (rtId, _fn) = runCatching { m.resolveTarget(u.target) }
                .getOrElse {
                    out += Issue(Level.ERROR, "$p.target", "target 格式应为 runtimeId:fn —— ${it.message}")
                    return@forEachIndexed
                }

            if (u.runtime.isBlank()) {
                out += Issue(Level.WARN, "$p.runtime", "为空，已从 target 推断为 $rtId（建议显式声明）")
            } else if (u.runtime != rtId && u.runtime != "mcp") {
                out += Issue(Level.ERROR, "$p.runtime", "与 target 前缀 '$rtId' 不一致")
            }

            val rt = m.runtime.firstOrNull { it.id == rtId }
            if (rt == null) {
                out += Issue(Level.ERROR, "$p.target", "引用了不存在的 runtime: $rtId")
            } else if (rt.exports.isNotEmpty() && _fn !in rt.exports) {
                out += Issue(Level.ERROR, "$p.target", "runtime '${rt.id}' 声明了 exports，但未包含函数 '$_fn'")
            }

            u.capabilities.forEach { c ->
                if (c !in knownCapabilities)
                    out += Issue(Level.ERROR, "$p.capabilities", "未知能力: $c")
                if (c !in m.capabilities)
                    out += Issue(Level.WARN, "$p.capabilities", "$c 未在包级 capabilities 声明，安装时不会展示给用户")
            }
            if (u.timeoutMs < 100) out += Issue(Level.WARN, "$p.timeoutMs", "过小")
            if (u.stream && u.concurrency > 1)
                out += Issue(Level.WARN, "$p", "流式 unit 并发 >1 会导致输出交错")
        }

        m.capabilities.forEach {
            if (it !in knownCapabilities) out += Issue(Level.ERROR, "capabilities", "未知能力: $it")
        }

        val s = m.sandbox
        if (s.memoryMb < 8) out += Issue(Level.ERROR, "sandbox.memoryMb", "至少 8MB")
        if (s.memoryMb > 512) out += Issue(Level.WARN, "sandbox.memoryMb", ">512MB 在移动端会拖垮宿主")
        if (s.netEgress == NetEgress.ALLOWLIST && s.allowHosts.isEmpty())
            out += Issue(Level.ERROR, "sandbox.allowHosts", "netEgress=allowlist 时必须给出 allowHosts")
        if (s.netEgress != NetEgress.DENY && "net.http" !in m.capabilities)
            out += Issue(Level.ERROR, "sandbox.netEgress", "放行网络但未声明 net.http 能力")
        if (s.allowExec && "proc.exec" !in m.capabilities)
            out += Issue(Level.ERROR, "sandbox.allowExec", "允许执行进程但未声明 proc.exec 能力")
        if (s.level == SandboxLevel.WASM_SANDBOX && m.runtime.any { it.lang !in listOf(Lang.WASM, Lang.TYPESCRIPT, Lang.JAVASCRIPT) })
            out += Issue(Level.ERROR, "sandbox.level", "wasm-sandbox 仅支持 wasm/js/ts 语言")

        val uiIds = mutableSetOf<String>()
        m.ui.forEachIndexed { i, u ->
            val p = "ui[$i]"
            if (!uiIds.add(u.id)) out += Issue(Level.ERROR, "$p.id", "ui id 重复: ${u.id}")
            val (rtId, _) = runCatching { m.resolveTarget(u.render) }.getOrElse {
                out += Issue(Level.ERROR, "$p.render", it.message ?: "格式错误"); return@forEachIndexed
            }
            if (m.runtime.none { it.id == rtId })
                out += Issue(Level.ERROR, "$p.render", "引用了不存在的 runtime: $rtId")
            u.onAction?.let { act ->
                val (aRt, _) = runCatching { m.resolveTarget(act) }.getOrElse { null to "" }
                if (aRt == null) out += Issue(Level.ERROR, "$p.onAction", "target 格式应为 runtimeId:fn")
                else if (m.runtime.none { it.id == aRt })
                    out += Issue(Level.ERROR, "$p.onAction", "引用了不存在的 runtime: $aRt")
            }
            if (u.surface != UiSurface.WIDGET && "ui.${u.surface.name.lowercase().replace('_', '-')}" !in m.capabilities
                && "ui.render" !in m.capabilities
            ) out += Issue(Level.WARN, "$p", "surface=${u.surface} 建议声明对应 ui.* 能力")
        }

        m.hooks.forEachIndexed { i, h ->
            val p = "hooks[$i]"
            if (h.point !in HookMatrix.ALL_POINTS)
                out += Issue(Level.ERROR, "$p.point", "未知 Hook 点: ${h.point}（可用: ${HookMatrix.ALL_POINTS.size} 个）")
            val (rtId, _) = runCatching { m.resolveTarget(h.target) }.getOrElse {
                out += Issue(Level.ERROR, "$p.target", it.message ?: "格式错误"); return@forEachIndexed
            }
            if (m.runtime.none { it.id == rtId })
                out += Issue(Level.ERROR, "$p.target", "引用了不存在的 runtime: $rtId")
            if (h.mode == HookMode.OBSERVE && h.priority < 0)
                out += Issue(Level.WARN, "$p", "observe 模式优先级无意义")
        }

        val resKeys = mutableSetOf<String>()
        m.resources.forEachIndexed { i, r ->
            if (!resKeys.add(r.key)) out += Issue(Level.ERROR, "resources[$i].key", "资源 key 重复: ${r.key}")
            if (r.path.contains("..")) out += Issue(Level.ERROR, "resources[$i].path", "路径不允许含 ..")
            if (!Regex("^[A-Za-z_][A-Za-z0-9_.]*$").containsMatchIn(r.key))
                out += Issue(Level.ERROR, "resources[$i].key", "key 命名不合规范")
        }

        if (m.deps.mcp.isNotEmpty() && "net.mcp" !in m.capabilities && "ipc.mcp" !in m.capabilities)
            out += Issue(Level.ERROR, "deps.mcp", "声明 MCP 依赖需 net.mcp 或 ipc.mcp 能力")
        m.deps.mcp.forEach { if (it.command == null && it.url == null) out += Issue(Level.ERROR, "deps.mcp", "${it.name} 缺少 command 或 url") }
        if (m.deps.npm.isNotEmpty() && m.runtime.none { it.lang in listOf(Lang.JAVASCRIPT, Lang.TYPESCRIPT) })
            out += Issue(Level.WARN, "deps.npm", "声明 npm 依赖但没有 js/ts runtime")

        return out
    }

    fun requireValid(m: KaleidoManifest, known: Set<String> = CapabilityLexicon.all) {
        val errs = validate(m, known).filter { it.level == Level.ERROR }
        if (errs.isNotEmpty())
            throw KaleidoException.Manifest("清单校验失败(${errs.size}):\n" + errs.joinToString("\n"))
    }

    private fun engineSupports(e: EngineKind, l: Lang) = when (e) {
        EngineKind.QUICKJS -> l in listOf(Lang.JAVASCRIPT, Lang.TYPESCRIPT)
        EngineKind.LUAJIT -> l == Lang.LUA
        EngineKind.CPYTHON -> l == Lang.PYTHON
        EngineKind.WASM -> l in listOf(Lang.WASM, Lang.TYPESCRIPT, Lang.JAVASCRIPT)
        EngineKind.JVM_DEX -> l in listOf(Lang.KOTLIN, Lang.JAVA)
        EngineKind.NODE -> l in listOf(Lang.JAVASCRIPT, Lang.TYPESCRIPT, Lang.SHELL)
        EngineKind.AUTO -> true
    }
}
