package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.plugin.PluginSigning
import com.ai.assistance.quro.core.plugin.QuroPluginAciRegistry
import com.ai.assistance.quro.core.plugin.QuroPluginHost
import com.ai.assistance.quro.plugin.engine.bridge.HostToolBridge
import com.ai.assistance.quro.plugin.extension.ExtensionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ★ APK 级插件框架的**唯一** AI 工具：`apk_plugin`
 *
 * 设计（对齐用户诉求「整个 apk 构架注册成一个工具让 AI 无缝操控插件」）：
 * 以前宿主把插件框架拆成 6 个零散工具（plugin_list / plugin_info / plugin_install /
 * plugin_uninstall / plugin_reload / plugin_surface_open），既占 tools 字段 token，
 * 又让模型要在 6 个名字里挑。现在收敛成**一个**工具，用 `action` 分发全部动作，
 * 模型只需记住 `apk_plugin`。
 *
 * 关键能力 `action=call`：AI 可以直接按名字调用**任意插件贡献的 AI 工具**，
 * 即使该工具这一轮没有进会话工具集（例如工具集被裁剪、或插件刚装完还没轮到下一轮）。
 * 这解决了「插件工具没注入会话工具集 → AI 完全用不了插件」的死角。
 *
 * 插件自己贡献的 AI 工具仍会照常出现在工具集里（[pluginHostToolSpecs]），
 * `call` 只是兜底通道，两条路都通。
 */

/** 框架动作全集（写进 enum，模型按名调用） */
private val APK_PLUGIN_ACTIONS = listOf(
    "status",          // 框架总状态（引擎/已装/已加载/贡献工具数/内置包数）
    "list",            // 已装插件清单（含扩展点计数 + 贡献的 AI 工具名）
    "info",            // 单个插件详情（需 plugin_id）
    "tools",           // 插件贡献的全部 AI 工具（名字 + 说明 + 参数）
    "surfaces",        // 插件提供的界面清单
    "open",            // 打开插件界面（需 surface_id）
    "install",         // 从本地 APK 安装（需 path）
    "install_builtin", // 安装宿主随包内置的示例插件
    "uninstall",       // 卸载（需 plugin_id）
    "reload",          // 热重载（plugin_id 可选；不传=全部）
    "call",            // ★ 直接调用插件贡献的 AI 工具（需 name，可选 args）
)

class ApkPluginTool : QuroTool {

    override val name = "apk_plugin"

    override val description =
        "APK 级插件框架总控（ZorvAI 的插件系统只有一个入口，就是这个工具）。" +
            "插件是独立 APK，宿主用 DexClassLoader 装进来，插件注册「扩展点」给 AI 加能力（AI 工具 / ACI 能力 / 斜杠指令 / 插件界面等），宿主不用改代码。" +
            "动作用 action 指定：" +
            "status=看框架状态；" +
            "list=列出已装插件及其扩展点；" +
            "info=某插件明细；" +
            "tools=列出插件贡献的全部 AI 工具（先看这个再决定调什么）；" +
            "surfaces=列出插件自带界面；open=打开插件界面；" +
            "install=从 APK 文件装插件；install_builtin=一键装宿主内置示例插件；" +
            "uninstall=卸载；reload=热重载（改完插件立刻生效，不用重启）；" +
            "call=★直接调用某个插件 AI 工具（name=工具名, args=参数JSON）——" +
            "即使该插件工具没出现在你的工具集里，也能通过本动作调用，所以「用某个插件的能力」永远先试 apk_plugin(action=\"call\", ...)。" +
            "用户说「装插件/卸载插件/重载插件/插件能干什么/打开插件界面/用某个插件查一下」时用本工具。"

    override val parametersJson = """
    {
      "type": "object",
      "properties": {
        "action": {
          "type": "string",
          "description": "要执行的动作",
          "enum": ["status", "list", "info", "tools", "surfaces", "open", "install", "install_builtin", "uninstall", "reload", "call"]
        },
        "plugin_id": { "type": "string", "description": "插件包名（info / uninstall / reload 用；reload 不传则重载全部）" },
        "surface_id": { "type": "string", "description": "插件界面 id（open 用；不传或 launcher 则只列出可用界面）" },
        "path": { "type": "string", "description": "插件 APK 的绝对路径（install 用），如 /storage/emulated/0/Download/xxx-plugin.apk" },
        "name": { "type": "string", "description": "插件贡献的 AI 工具名（call 用），如 dev_uuid / unit_convert / todo_add" },
        "args": { "type": "string", "description": "可选。call 的参数，JSON 对象字符串，如 {\"text\":\"hi\"}" },
        "skip_signature_check": { "type": "boolean", "description": "可选。install 时跳过同签名校验（仅调试，默认 false）" }
      },
      "required": ["action"]
    }
    """.trimIndent()

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val action = jo.optString("action", "status").trim().ifEmpty { "status" }
        return try {
            when (action) {
                "status" -> status(context)
                "list" -> listPlugins()
                "info" -> info(jo.optString("plugin_id").trim())
                "tools" -> pluginTools()
                "surfaces" -> surfaces()
                "open" -> open(context, jo.optString("surface_id").trim())
                "install" -> install(context, jo.optString("path").trim(), jo.optBoolean("skip_signature_check", false))
                "install_builtin" -> installBuiltin(context)
                "uninstall" -> uninstall(jo.optString("plugin_id").trim())
                "reload" -> reload(jo.optString("plugin_id").trim())
                "call" -> call(jo)
                else -> "未知 action：$action\n支持：${APK_PLUGIN_ACTIONS.joinToString(" / ")}"
            }
        } catch (t: Throwable) {
            "apk_plugin($action) 执行异常：${t.message ?: t.javaClass.simpleName}"
        }
    }

    // ─────────────────────────── 各动作实现 ───────────────────────────

    private fun status(context: Context): String {
        if (!QuroPluginHost.isReady()) return NOT_READY
        val installed = runCatching { QuroPluginHost.installed() }.getOrElse { emptyList() }
        val loaded = runCatching { QuroPluginHost.loadedIds().size }.getOrDefault(0)
        val tools = runCatching { HostToolBridge.collectToolSpecs() }.getOrElse { emptyList() }
        val surfaces = runCatching { HostToolBridge.uiSurfaces() }.getOrElse { emptyList() }
        val aciCaps = QuroPluginAciRegistry.capabilities().size
        val builtin = builtinApkNames(context).size
        return JSONObject().apply {
            put("engine_ready", true)
            put("installed", installed.size)
            put("loaded", loaded)
            put("plugin_ai_tools", tools.size)
            put("plugin_aci_capabilities", aciCaps)
            put("plugin_surfaces", surfaces.size)
            put("builtin_packages_in_assets", builtin)
            put("hint", "用 action=list 看插件清单；action=tools 看插件给了哪些可调用的 AI 工具；" +
                "action=call 直接调某个插件工具。插件桌面界面：ui_open_plugins。")
        }.toString(2)
    }

    private fun listPlugins(): String {
        if (!QuroPluginHost.isReady()) return NOT_READY
        val records = runCatching { QuroPluginHost.installed() }.getOrElse { emptyList() }
        if (records.isEmpty()) {
            return "当前没有安装任何插件。"
        }
        val arr = JSONArray()
        records.forEach { r ->
            val exts = runCatching { QuroPluginHost.extensionSummary(r.pluginId) }.getOrDefault(emptyMap())
            arr.put(JSONObject().apply {
                put("plugin_id", r.pluginId)
                put("name", r.name)
                put("version", "${r.versionName}(${r.versionCode})")
                put("loaded", QuroPluginHost.isLoaded(r.pluginId))
                put("extension_count", exts.values.sum())
                put("extensions", JSONObject(exts as Map<*, *>))
                put("ai_tools", JSONArray(aiToolNamesOf(r.pluginId)))
                put("surfaces", JSONArray(surfaceIdsOf(r.pluginId)))
            })
        }
        return arr.toString(2)
    }

    private fun info(pluginId: String): String {
        if (!QuroPluginHost.isReady()) return NOT_READY
        if (pluginId.isEmpty()) return "缺少参数 plugin_id（用 action=list 查看已装插件包名）"
        val rec = runCatching { QuroPluginHost.installed().firstOrNull { it.pluginId == pluginId } }.getOrNull()
        if (rec == null) return "未安装插件：$pluginId"

        val all = runCatching {
            com.ai.assistance.quro.plugin.engine.registry.ExtensionRegistry.allOfPlugin(pluginId)
        }.getOrElse { emptyList() }
        val byType = JSONObject()
        all.groupBy { it.first.name }.forEach { (type, list) -> byType.put(type, JSONArray(list.map { it.second.id })) }

        return JSONObject().apply {
            put("plugin_id", pluginId)
            put("name", rec.name)
            put("version", "${rec.versionName}(${rec.versionCode})")
            put("entry_class", rec.entryClass)
            put("apk_path", rec.apkPath)
            put("loaded", QuroPluginHost.isLoaded(pluginId))
            put("extensions", byType)
            put("ai_tools", JSONArray(aiToolNamesOf(pluginId)))
            put("surfaces", JSONArray(surfaceIdsOf(pluginId)))
            put("hint", "要调用本插件的 AI 工具：apk_plugin(action=\"call\", name=\"工具名\", args=\"{...}\")")
        }.toString(2)
    }

    /** 插件贡献的全部 AI 工具（名称 / 说明 / 归属插件 / 参数） */
    private fun pluginTools(): String {
        if (!QuroPluginHost.isReady()) return NOT_READY
        val specs = runCatching { HostToolBridge.collectToolSpecs() }.getOrElse { emptyList() }
        if (specs.isEmpty()) {
            return "当前没有任何插件贡献 AI 工具。可先 apk_plugin(action=\"install_builtin\") 装宿主内置的示例插件。"
        }
        val arr = JSONArray()
        specs.forEach { s ->
            val pid = runCatching {
                com.ai.assistance.quro.plugin.engine.registry.ExtensionRegistry
                    .pluginIdOf(ExtensionType.AI_TOOL, s.name)
            }.getOrNull()
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("plugin_id", pid ?: "?")
                put("description", s.description)
                put("parameters", JSONArray(s.parameters.map { p ->
                    JSONObject().apply {
                        put("name", p.name)
                        put("type", p.type.jsonType)
                        put("required", p.required)
                        put("description", p.description)
                    }
                }))
            })
        }
        return JSONObject().apply {
            put("count", specs.size)
            put("tools", arr)
            put("hint", "这些工具通常已直接出现在你的工具集里，可直接调用；若没有，用 " +
                "apk_plugin(action=\"call\", name=\"<工具名>\", args=\"{...}\") 调用。")
        }.toString(2)
    }

    private fun surfaces(): String {
        if (!QuroPluginHost.isReady()) return NOT_READY
        val list = runCatching { HostToolBridge.uiSurfaces() }.getOrElse { emptyList() }
        if (list.isEmpty()) return "当前没有插件提供界面。"
        return JSONObject().apply {
            put("count", list.size)
            put("surfaces", JSONArray(list.map { ext ->
                JSONObject().apply {
                    put("surface_id", ext.id)
                    put("label", ext.label)
                    put("title", ext.title)
                    put("plugin_id", HostToolBridge.pluginIdOfSurface(ext.id) ?: "?")
                }
            }))
            put("hint", "用 apk_plugin(action=\"open\", surface_id=\"<id>\") 打开。")
        }.toString(2)
    }

    private fun open(context: Context, surfaceId: String): String {
        if (!QuroPluginHost.isReady()) return NOT_READY
        val list = runCatching { HostToolBridge.uiSurfaces() }.getOrElse { emptyList() }
        if (surfaceId.isEmpty() || surfaceId == "launcher") {
            // 无 id：让 AI 先看到有哪些界面（插件桌面本体在对话框「插件」入口 / ui_open_plugins）
            return if (list.isEmpty()) "当前没有插件提供界面。"
            else "可用插件界面（${list.size}）：\n" + list.joinToString("\n") {
                "- ${it.id} | ${it.label} | 来自 ${HostToolBridge.pluginIdOfSurface(it.id) ?: "?"}"
            } + "\n\n用 apk_plugin(action=\"open\", surface_id=\"<id>\") 打开指定界面；" +
                "要打开「插件桌面」本体（启动器式管理界面）请用 ui_open_plugins。"
        }
        val ext = HostToolBridge.surface(surfaceId) ?: return "没有找到插件界面：$surfaceId"
        return try {
            context.startActivity(
                com.ai.assistance.quro.ui.PluginSurfaceActivity.intent(context, surfaceId, ext.title)
            )
            "已打开插件界面：${ext.label}（$surfaceId）"
        } catch (t: Throwable) {
            "打开失败：${t.message ?: t.javaClass.simpleName}（若在后台执行，请让用户手动打开插件界面）"
        }
    }

    private fun install(context: Context, path: String, skipSignature: Boolean): String {
        if (!QuroPluginHost.isReady()) return NOT_READY
        if (path.isEmpty()) return "缺少参数 path"
        val apk = File(path)
        if (!apk.exists()) return "文件不存在：$path"

        // 与「导入 APK」保持同一条路径：先用宿主密钥补签（若用户配过签名），再走同签名校验安装。
        // 补签失败不阻断安装 —— 回退装原包，把原因带回去。
        var target = apk
        var signNote = ""
        val cfg = runCatching { PluginSigning.load(context) }.getOrNull()
        if (!skipSignature && cfg != null && cfg.enabled) {
            val signed = File(context.cacheDir, "signed-${apk.name}")
            if (signed.exists()) signed.delete()
            val err = PluginSigning.sign(context, apk, signed)
            if (err == null) {
                target = signed
                signNote = "\n· 已用「${cfg.keystoreName}」(别名 ${cfg.alias}) 补签后再安装"
            } else {
                signNote = "\n· 补签未成功，已按原包安装：$err"
            }
        }

        // 补签成功 = 这个包是本机用自己的密钥刚签出来的，再拿「是否与宿主同签名」卡它是同义反复，直接放行
        val resigned = target.absolutePath != apk.absolutePath
        val r = QuroPluginHost.install(target, requireSameSignature = !skipSignature && !resigned)
        if (target.absolutePath != apk.absolutePath) target.delete()
        if (!r.success) return "插件安装失败：${r.message}$signNote"
        val exts = runCatching { QuroPluginHost.extensionSummary(r.pluginId) }.getOrDefault(emptyMap())
        return "插件安装成功：${r.pluginId}$signNote\n" +
            "贡献扩展点：${exts.entries.joinToString { "${it.key}×${it.value}" }.ifBlank { "无" }}\n" +
            "新增 AI 工具：${aiToolNamesOf(r.pluginId).joinToString("、").ifBlank { "无" }}\n" +
            "（这些工具下一轮起会直接出现在你的工具集里；本工具 action=\"tools\" 可随时查看。）"
    }

    private fun installBuiltin(context: Context): String {
        if (!QuroPluginHost.isReady()) return NOT_READY
        val names = builtinApkNames(context)
        if (names.isEmpty()) return "宿主未内置示例插件。"
        val sb = StringBuilder()
        var ok = 0
        names.forEach { n ->
            val tmp = File(context.cacheDir, "builtin_$n")
            runCatching {
                context.assets.open("plugins/$n").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                val r = QuroPluginHost.install(tmp)
                if (r.success) {
                    ok++
                    sb.appendLine("✓ 安装成功：${r.pluginId}（AI 工具：${
                        aiToolNamesOf(r.pluginId).joinToString("、").ifBlank { "无" }
                    }）")
                } else {
                    sb.appendLine("✗ 安装失败：$n → ${r.message}")
                }
            }.onFailure { sb.appendLine("✗ 安装异常：$n → ${it.message}") }
            tmp.delete()
        }
        return "内置插件安装完成：成功 $ok / 共 ${names.size} 个\n$sb" +
            "（装完可直接 apk_plugin(action=\"tools\") 看新增了哪些可调用工具。）"
    }

    private fun uninstall(pluginId: String): String {
        if (!QuroPluginHost.isReady()) return NOT_READY
        if (pluginId.isEmpty()) return "缺少参数 plugin_id"
        return if (QuroPluginHost.uninstall(pluginId)) {
            "插件已卸载：$pluginId（它贡献的 AI 工具 / ACI 能力 / 界面等一并移除）"
        } else {
            "未找到插件：$pluginId"
        }
    }

    private fun reload(pluginId: String): String {
        if (!QuroPluginHost.isReady()) return NOT_READY
        if (pluginId.isNotEmpty()) {
            return if (QuroPluginHost.reload(pluginId)) "插件已重载：$pluginId"
            else "重载失败：$pluginId（未安装或入口类加载异常）"
        }
        val results = runCatching { QuroPluginHost.installed() }.getOrElse { emptyList() }.map { rec ->
            "${rec.pluginId}: ${if (QuroPluginHost.reload(rec.pluginId)) "OK" else "失败"}"
        }
        return if (results.isEmpty()) "没有已安装的插件。" else "重载结果：\n" + results.joinToString("\n")
    }

    /** ★ 直接调用插件贡献的 AI 工具（绕过工具集裁剪，兜底通道） */
    private fun call(jo: JSONObject): String {
        if (!QuroPluginHost.isReady()) return NOT_READY
        val toolName = jo.optString("name").trim()
        if (toolName.isEmpty()) {
            return "缺少参数 name。用 action=\"tools\" 查看可调用的插件工具名，" +
                "例如 apk_plugin(action=\"call\", name=\"unit_convert\", args=\"{\\\"value\\\":1,\\\"from\\\":\\\"km\\\",\\\"to\\\":\\\"m\\\"}\")"
        }
        if (!QuroPluginHost.hasPluginTool(toolName)) {
            return "没有名为「$toolName」的插件工具。现有插件工具：" +
                runCatching { HostToolBridge.collectToolSpecs() }.getOrElse { emptyList() }
                    .joinToString("、") { it.name }.ifBlank { "（无）" }
        }
        val argsRaw = jo.optString("args").trim()
        val argsMap: Map<String, Any?> = if (argsRaw.isEmpty()) emptyMap()
        else runCatching { jsonToMap(argsRaw) }.getOrElse { return "args 不是合法 JSON 对象：$argsRaw" }
        val out = runBlocking(Dispatchers.IO) {
            runCatching { QuroPluginHost.executePluginTool(toolName, argsMap) }
                .getOrElse { "调用异常：${it.message}" }
        }
        return "插件工具 $toolName 返回：\n${out ?: "（无输出）"}"
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    // 宿主 assets/plugins/ 下随包内置的示例插件（注意：这里不能写成块注释，
    // 因为 "plugins/*.apk" 里含 "/*"，而 Kotlin 块注释支持嵌套，会把后续代码整段吞掉）
    private fun builtinApkNames(context: Context): List<String> =
        runCatching { context.assets.list("plugins")?.toList() }.getOrNull().orEmpty()
            .filter { it.endsWith(".apk") }
            .sorted()

    /** 某插件贡献的 AI 工具名 */
    private fun aiToolNamesOf(pluginId: String): List<String> = runCatching {
        HostToolBridge.collectToolSpecs().filter { s ->
            com.ai.assistance.quro.plugin.engine.registry.ExtensionRegistry
                .pluginIdOf(ExtensionType.AI_TOOL, s.name) == pluginId
        }.map { it.name }
    }.getOrDefault(emptyList())

    /** 某插件提供的界面 id */
    private fun surfaceIdsOf(pluginId: String): List<String> = runCatching {
        HostToolBridge.uiSurfaces().filter { HostToolBridge.pluginIdOfSurface(it.id) == pluginId }
            .map { it.id }
    }.getOrDefault(emptyList())

    private companion object {
        private const val NOT_READY =
            "插件引擎未初始化（Application 启动阶段未执行到 QuroPluginHost.attach）。"
    }
}

/** 全部插件管理工具（供注册表批量注册）——现在只剩一个总控工具。 */
val allPluginManagementTools: List<QuroTool> = listOf(ApkPluginTool())

/** 插件管理工具名（并入 coreSpecs 默认下发集，保证 AI 开箱可见） */
val pluginManagementToolNames: Set<String> = allPluginManagementTools.map { it.name }.toSet()

/** 插件管理工具的规格（含已装插件动态贡献的工具） */
fun pluginHostToolSpecs(): List<com.ai.assistance.quro.core.QuroToolSpec> =
    allPluginManagementTools.map {
        com.ai.assistance.quro.core.QuroToolSpec(it.name, it.description, it.parametersJson)
    } + QuroPluginHost.toolSpecs()
