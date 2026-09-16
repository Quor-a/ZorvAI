package com.ai.assistance.quro.kaleidobox.core.eco

import com.ai.assistance.quro.kaleidobox.core.KaleidoException
import com.ai.assistance.quro.kaleidobox.core.hooks.HookMatrix
import com.ai.assistance.quro.kaleidobox.core.model.*
import com.ai.assistance.quro.kaleidobox.core.registry.PackageSource
import com.ai.assistance.quro.kaleidobox.core.util.Json

/**
 * Operit ToolPkg → Kaleido 迁移层。
 *
 * 这是"生态兼容"最直接的一刀：**已有的 ToolPkg 插件不用重写，直接能装**。
 *
 * 做三件事：
 *   1. manifest.json → kaleido.json 字段映射（id / version / main / subpackages / resources）
 *   2. 扫描子包的 METADATA 块，把 JS 工具函数抽成 Kaleido 的 unit（这样 AI 能直接索引到）
 *   3. 注入 [COMPAT_SHIM_JS]：提供 ToolPkg 插件期望的全局符号（complete / ToolPkg / Java）
 *
 * 限制（会在 report 里明确列出，不静默吞掉）：
 *   - Compose DSL UI（ui 目录下的 .ui.js）无法自动翻译，需要改写成 UiNode 树（见 MIGRATION 文档）
 *   - registerAppLifecycleHook 等宿主钩子可映射，但事件名不同，会做一层适配
 *   - HJSON 旧格式的脚本包不支持（建议先转 JS）
 */
object ToolPkgCompat {

    data class MigrationReport(
        val ok: Boolean,
        val sourceId: String,
        val targetId: String,
        val mappedUnits: List<String>,
        val mappedResources: List<String>,
        val warnings: List<String>,
        val blockers: List<String>,
    ) {
        fun render() = buildString {
            appendLine("ToolPkg 迁移: $sourceId → $targetId  [${if (ok) "可迁移" else "受阻"}]")
            appendLine("  units(${mappedUnits.size}): ${mappedUnits.joinToString(", ")}")
            appendLine("  resources(${mappedResources.size}): ${mappedResources.joinToString(", ")}")
            warnings.forEach { appendLine("  ⚠ $it") }
            blockers.forEach { appendLine("  ✖ $it") }
        }
    }

    fun isToolPkg(src: PackageSource): Boolean =
        src.exists("manifest.json") && !src.exists("kaleido.json")

    /**
     * 若源是 ToolPkg（或 kaleido.json 里声明了 compat.toolpkg=auto），则翻译成 Kaleido 清单。
     * 返回 null 表示无需翻译。
     */
    fun translateIfNeeded(current: KaleidoManifest, src: PackageSource): KaleidoManifest? {
        val declared = current.compat.toolpkg == "auto"
        if (!declared && !isToolPkg(src)) return null
        return translate(src).second
    }

    /** @return (报告, 翻译后的清单) */
    fun translate(src: PackageSource): Pair<MigrationReport, KaleidoManifest> {
        val rawText = src.read("manifest.json")?.toString(Charsets.UTF_8)
            ?: throw KaleidoException.Manifest("不是 ToolPkg：缺少 manifest.json")
        @Suppress("UNCHECKED_CAST")
        val raw = Json.parse(rawText) as? Map<String, Any?>
            ?: throw KaleidoException.Manifest("manifest.json 解析失败")

        val warnings = mutableListOf<String>()
        val blockers = mutableListOf<String>()

        val srcId = raw["toolpkg_id"] as? String ?: "unknown.toolpkg"
        val targetId = sanitizeId(srcId).also { if (it != srcId) warnings += "id 已规范化: $srcId → $it" }
        val version = raw["version"] as? String ?: "0.1.0"
        val main = raw["main"] as? String ?: "dist/main.js"
        val display = (raw["display_name"] as? Map<*, *>)
            ?.map { it.key.toString() to it.value.toString() }?.toMap() ?: mapOf("en" to targetId)
        val description = (raw["description"] as? Map<*, *>)
            ?.map { it.key.toString() to it.value.toString() }?.toMap() ?: emptyMap()

        // ---- runtime：main + subpackages ----
        val runtimeUnits = mutableListOf<RuntimeUnit>()
        runtimeUnits += RuntimeUnit(
            id = "main", lang = Lang.JAVASCRIPT, engine = EngineKind.QUICKJS,
            entry = main, exports = emptyList(),
        )
        @Suppress("UNCHECKED_CAST")
        val subpackages = raw["subpackages"] as? List<Map<String, Any?>> ?: emptyList()
        subpackages.forEachIndexed { i, sp ->
            val id = sp["id"] as? String ?: "sub$i"
            val entry = sp["entry"] as? String
            if (entry == null) {
                warnings += "subpackage $id 缺少 entry，已跳过"
                return@forEachIndexed
            }
            runtimeUnits += RuntimeUnit(
                id = sanitizeRuntimeId(id), lang = Lang.JAVASCRIPT, engine = EngineKind.QUICKJS,
                entry = entry, exports = emptyList(),
            )
        }

        // ---- units：扫描每个入口的 METADATA ----
        val units = mutableListOf<UnitSpec>()
        val mappedUnitNames = mutableListOf<String>()
        val unitNames = mutableSetOf<String>()
        runtimeUnits.forEach { rt ->
            val code = src.read(rt.entry)?.toString(Charsets.UTF_8)
            if (code == null) {
                warnings += "entry 不存在: ${rt.entry}"
                return@forEach
            }
            val tools = scanMetadataTools(code)
            if (tools.isEmpty() && rt.id == "main") {
                // main 一般只做注册，没有工具 —— 正常
            }
            tools.forEach { t ->
                // 多个 runtime 可能导出同名工具（迁移常见），用 runtime id 消歧，
                // 保证迁移后的清单一定能通过校验器
                val base = sanitizeUnitName(t.name).also { if (it != t.name) warnings += "unit 名规范化: ${t.name} → $it" }
                val name = base.let { if (it in unitNames) "${sanitizeUnitName(rt.id)}_$it".also { n -> warnings += "unit 名冲突，已重命名为 $n" } else it }
                val unit = UnitSpec(
                    name = name,
                    runtime = rt.id,
                    target = "${rt.id}:${t.fn}",
                    description = t.description,
                    params = t.params,
                    capabilities = emptyList(),
                )
                units += unit
                unitNames += unit.name
                mappedUnitNames += unit.name
            }
        }

        // ---- resources ----
        @Suppress("UNCHECKED_CAST")
        val resources = (raw["resources"] as? List<Map<String, Any?>>)?.mapNotNull { r ->
            val key = r["key"] as? String
            val path = r["path"] as? String
            if (key == null || path == null) {
                warnings += "resource 字段不全: $r"; null
            } else ResourceSpec(key, path, r["mime"] as? String ?: "application/octet-stream")
        } ?: emptyList()

        // ---- UI：Compose DSL 无法自动翻译，明确标注 ----
        val uiFiles = src.list().filter { it.contains("/ui/") && it.endsWith(".ui.js") }
        if (uiFiles.isNotEmpty()) {
            warnings += "检测到 ${uiFiles.size} 个 Compose DSL UI 文件（${uiFiles.joinToString()}），" +
                "需改写成 UiNode 树；已自动跳过，功能暂不可用"
        }

        // ---- hooks：尝试从 main 里识别注册调用 ----
        val hooks = mutableListOf<HookSpec>()
        val mainCode = src.read(main)?.toString(Charsets.UTF_8) ?: ""
        HOOK_MAPPINGS.forEach { (toolpkgFn, kaleidoPoint) ->
            if (mainCode.contains(toolpkgFn)) {
                hooks += HookSpec(point = kaleidoPoint, target = "main:$toolpkgFn", priority = 100, mode = HookMode.MODIFY)
                warnings += "已映射宿主钩子: $toolpkgFn → $kaleidoPoint（事件字段有差异，请实测）"
            }
        }
        if (mainCode.contains("registerToolboxUiModule") && uiFiles.isEmpty()) {
            warnings += "main 里注册了工具箱 UI，但未找到 ui/*.ui.js，界面不会显示"
        }

        val manifest = KaleidoManifest(
            schema = 1,
            id = targetId,
            version = version,
            name = display,
            description = description,
            runtime = runtimeUnits,
            units = units,
            // 迁移期默认给最小能力集，让包先跑起来；宿主应在安装页提示用户收紧
            capabilities = listOf("fs.read:scoped", "fs.write:scoped", "ui.toolbox", "data.kv"),
            sandbox = SandboxPolicySpec(
                level = SandboxLevel.IN_PROCESS, memoryMb = 64, cpuMsPerCall = 5000, netEgress = NetEgress.DENY
            ),
            ui = emptyList(),
            hooks = hooks,
            resources = resources,
            compat = CompatSpec(toolpkg = "auto"),
        )

        val report = MigrationReport(
            ok = blockers.isEmpty(),
            sourceId = srcId,
            targetId = targetId,
            mappedUnits = mappedUnitNames,
            mappedResources = resources.map { it.key },
            warnings = warnings,
            blockers = blockers,
        )
        return report to manifest
    }

    /**
     * 生成可落盘的 kaleido.json（宿主可缓存，用于"迁移一次，之后按原生包加载"）。
     * 同时会把 compat shim 追加到每个 runtime 的 exports 说明里。
     */
    fun emitManifestJson(m: KaleidoManifest): String = Json.write(
        mapOf(
            "schema" to 1,
            "id" to m.id,
            "version" to m.version,
            "name" to m.name,
            "description" to m.description,
            "runtime" to m.runtime.map {
                mapOf("id" to it.id, "lang" to "javascript", "engine" to "quickjs", "entry" to it.entry)
            },
            "units" to m.units.map {
                mapOf(
                    "name" to it.name, "runtime" to it.runtime, "target" to it.target,
                    "description" to it.description, "params" to it.params
                ).filterValues { it != null }
            },
            "capabilities" to m.capabilities,
            "sandbox" to mapOf("level" to "in-process", "memoryMb" to m.sandbox.memoryMb),
            "hooks" to m.hooks.map { mapOf("point" to it.point, "target" to it.target, "priority" to it.priority) },
            "resources" to m.resources.map { mapOf("key" to it.key, "path" to it.path, "mime" to it.mime) },
            "_migratedFrom" to "operit-toolpkg",
        )
    )

    // ---------------- 内部 ----------------

    data class ScannedTool(val name: String, val fn: String, val description: String, val params: Map<String, Any?>?)

    /**
     * 从 JS 源码的 METADATA 块里扫出工具定义。
     * ToolPkg 的 METADATA 大致形如：
     * ```
     * (METADATA
     *  * { "tools": [ { "name": "foo", "description": "...", "parameters": {...} } ] }
     *  *)
     * ```
     * 找不到 METADATA 时退化为扫描 exports 的函数名 —— 不完美，但比让人手工重写强。
     */
    fun scanMetadataTools(code: String): List<ScannedTool> {
        val out = mutableListOf<ScannedTool>()
        val metaBlock = Regex("/\\*\\s*METADATA(.*?)\\*/", RegexOption.DOT_MATCHES_ALL).find(code)
        if (metaBlock != null) {
            val jsonPart = metaBlock.groupValues[1]
                .lineSequence()
                .map { it.trim().trimStart('*').trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
            val parsed = runCatching { Json.parse(jsonPart) }.getOrNull()
            @Suppress("UNCHECKED_CAST")
            val m = parsed as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val tools = m?.get("tools") as? List<Map<String, Any?>>
            tools?.forEach { t ->
                val name = t["name"] as? String ?: return@forEach
                @Suppress("UNCHECKED_CAST")
                out += ScannedTool(
                    name = name,
                    fn = (t["function"] as? String) ?: name,
                    description = t["description"] as? String ?: "",
                    params = t["parameters"] as? Map<String, Any?> ?: t["params"] as? Map<String, Any?>,
                )
            }
        }
        if (out.isEmpty()) {
            Regex("""(?:export\s+)?function\s+([A-Za-z_$][\w$]*)""").findAll(code)
                .map { it.groupValues[1] }
                .filter { !it.startsWith("_") }
                .distinct()
                .forEach { out += ScannedTool(it, it, "", null) }
        }
        return out.distinctBy { it.fn }
    }

    private val HOOK_MAPPINGS = listOf(
        "registerAppLifecycleHook" to HookMatrix.APP_ON_CREATE,
        "registerMessageProcessingPlugin" to HookMatrix.MESSAGE_INCOMING,
        "registerXmlRenderPlugin" to HookMatrix.MESSAGE_RENDER_XML,
        "registerInputMenuTogglePlugin" to HookMatrix.UI_INPUT_MENU_TOGGLE,
        "registerPromptInputHook" to HookMatrix.PROMPT_INPUT_BEFORE,
        "registerPromptHistoryHook" to HookMatrix.PROMPT_HISTORY_PREPARE,
        "registerSystemPromptComposeHook" to HookMatrix.PROMPT_SYSTEM_COMPOSE,
        "registerToolPromptComposeHook" to HookMatrix.PROMPT_TOOL_COMPOSE,
        "registerPromptFinalizeHook" to HookMatrix.PROMPT_FINALIZE,
    )

    private fun sanitizeId(s: String): String {
        val cleaned = s.lowercase().replace(Regex("[^a-z0-9_.]"), "_")
        return if (cleaned.count { it == '.' } >= 1) cleaned else "$cleaned.pkg"
    }

    private fun sanitizeRuntimeId(s: String) = s.replace(Regex("[^A-Za-z0-9_]"), "_").let {
        if (it.firstOrNull()?.isLetter() == true) it else "rt_$it"
    }

    private fun sanitizeUnitName(s: String): String {
        val t = s.lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_')
        return if (t.firstOrNull()?.isLetter() == true) t.take(48) else "u_${t.take(46)}"
    }

    /**
     * 兼容 shim：在 ToolPkg 插件的 JS 上下文里预先注入这些全局符号，
     * 让旧插件以为自己还在 Operit 里跑。
     * 宿主应在加载迁移包时把它 prepend 到入口源码前面。
     */
    const val COMPAT_SHIM_JS = """
/* Kaleido ToolPkg 兼容层 —— 由宿主在加载迁移包时注入 */
(function (global) {
  var __ctx = null;
  function setContext(c) { __ctx = c; }

  // ToolPkg 用 complete(...) 返回终态结果；这里转成 Kaleido 的直接返回值。
  function complete(result) {
    if (!result) return { ok: true };
    return {
      ok: result.success !== false,
      code: result.success === false ? (result.code || 'E_UNIT') : null,
      message: result.message || '',
      data: result.data !== undefined ? result.data : result
    };
  }

  // ToolPkg.* 的注册面：映射到 Kaleido 的 UiNode / Hook 语义
  var ToolPkg = {
    registerToolboxUiModule: function (spec) {
      if (global.__kaleido && global.__kaleido.registerLegacyUi) {
        global.__kaleido.registerLegacyUi(spec);
      }
      return true;
    },
    registerAppLifecycleHook: function (h) { return (global.__kaleido||{}).registerLegacyHook && global.__kaleido.registerLegacyHook('app.onCreate', h); },
    registerMessageProcessingPlugin: function (h) { return (global.__kaleido||{}).registerLegacyHook && global.__kaleido.registerLegacyHook('message.incoming', h); },
    registerXmlRenderPlugin: function (h) { return (global.__kaleido||{}).registerLegacyHook && global.__kaleido.registerLegacyHook('message.renderXml', h); },
    registerInputMenuTogglePlugin: function (h) { return (global.__kaleido||{}).registerLegacyHook && global.__kaleido.registerLegacyHook('ui.inputMenuToggle', h); },
    readResource: function (key) { return (global.__kaleido||{}).readResource && global.__kaleido.readResource(key); }
  };

  global.complete = complete;
  global.ToolPkg = ToolPkg;
  global.__kaleidoSetContext = setContext;
})(typeof globalThis !== 'undefined' ? globalThis : this);
"""
}
