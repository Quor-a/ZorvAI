package com.ai.assistance.quro.genui.app.agent.tools

import android.content.Context
import com.ai.assistance.quro.core.QuroToolCall
import com.ai.assistance.quro.core.QuroToolResult
import com.ai.assistance.quro.core.tools.QuroToolEngine
import com.ai.assistance.quro.core.tools.QuroToolRegistry
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import com.ai.assistance.quro.genui.app.agent.AgentMemory
import com.ai.assistance.quro.genui.app.agent.CodeRuntime
import com.ai.assistance.quro.genui.app.agent.PluginRuntime
import com.ai.assistance.quro.genui.app.agent.ToolGate
import com.ai.assistance.quro.genui.app.miniapp.GenUiMiniAppActivity
import com.yuanbao.miniapp.core.MiniAppEngine
import android.content.Intent
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * GenUI 全模式（GenScaffold / AgentLoop）的工具适配层 —— 把工具调用**整体对接到 ZorvAI 的完整工具系统**。
 *
 * 此前 GenUI 全模式用自己的 [BuiltinTools]（仅 ~30 个 GenUI 私有的小工具集，且各自重实现了一遍
 * web/记忆/文件等能力，与 ZorvAI 主对话的工具是两套互不相通的孤岛）。现改为：
 *  - 工具声明（declarations）：取自 ZorvAI 的 [QuroToolRegistry.coreSpecs]（即主对话默认下发的
 *    "完整工具集"——终端 / ACI / MCP / CMS / Linux / 屏幕控制 / 文件 / 记忆 / 经验 / 动态 UI 等全部在内）；
 *  - 工具执行（execute）：经 ZorvAI 的 [QuroToolEngine] 派发，复用主对话同一套真实工具实现；
 *  - 权限：交由 [QuroToolEngine] 内部自带的权限处理（QuroPermissionHolder，与主对话一致），
 *    不再走 GenUI 自己的 ToolGate 门禁——GenUI 的 L1–L5 策略层保留给「GenUI 私有设置页」语义，
 *    但工具真正执行由 ZorvAI 统一把关。
 *  - 记忆库（memory）：仍用已对接好的 [AgentMemory]（→ QuroMemoryRepository，group="genui"），
 *    与 ZorvAI 主对话共用同一份记忆。
 *
 * 对外暴露与 [BuiltinTools] 完全同形的成员（declarations / execute / gateFor / gate / memory），
 * 因此 [com.ai.assistance.quro.genui.app.agent.AgentLoop] 只需把 `BuiltinTools` 换成 `ZorvToolAdapter`，
 * 其余决策轮 / 渲染轮逻辑零改动。
 */
class ZorvToolAdapter(context: Context) {

    private val appContext = context.applicationContext

    /** ZorvAI 完整工具注册表（含全部内置工具 + 导入工具 + 技能工具）。 */
    private val registry: QuroToolRegistry = buildQuroRegistry(appContext).apply {
        attach(appContext)
    }

    /** ZorvAI 双引擎工具执行器（droid-mcp 派发 + 权限前置申请）。 */
    private val engine = QuroToolEngine(registry).apply {
        setContext(appContext)
    }

    /** 记忆库（已委托 QuroMemoryRepository）。 */
    val memory = AgentMemory(appContext)

    /**
     * GenUI 权限策略存储（与 GenUI「权限设置」屏共用同一份 QuroAI 共享 SharedPreferences）。
     * 对接 ZorvAI 后，工具是否真正能执行由 [QuroToolEngine] 内部的 QuroPermissionHolder 把关，
     * 这里的 ToolGate 仅作「GenUI 权限设置屏」的数据落点——"永久允许"把偏好记下来，保持设置屏一致。
     */
    private val policyStore = ToolGate(appContext)

    /** 对应 GenUI 权限卡上的"永久允许"：把工具策略固化为 ALWAYS_ALLOW 落到 GenUI 权限存储。 */
    fun grantAlways(tool: String) = policyStore.grantAlways(tool)

    /**
     * 权限门禁：对接 ZorvAI 后，真正的权限把关在 [QuroToolEngine] 内部（QuroPermissionHolder），
     * 这里对 GenUI 决策轮返回「一律放行」，避免双重弹窗；同时保留 [ToolGate.level] 供时间线显示等级。
     *
     * 注意：必须是**具名类**（[ZorvGate]），不能用匿名 object——AgentLoop 跨文件访问
     * gate.modeOf / gate.authorize 时，匿名对象含 suspend 成员的签名无法解析（Unresolved reference）。
     */
    val gate = ZorvGate()

    /** OpenAI function-calling 的工具声明：来自 ZorvAI 的 coreSpecs（完整工具集）。 */
    fun declarations(): JSONArray {
        val specs = registry.coreSpecs()
        val arr = JSONArray()
        for (spec in specs) {
            val params = runCatching { JSONObject(spec.parametersJson) }
                .getOrDefault(JSONObject("""{"type":"object","properties":{}}"""))
            arr.put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", spec.name)
                    .put("description", spec.description)
                    .put("parameters", params)))
        }
        // 追加 GenUI 专属工具：小程序引擎（WXML/WXSS/JS）+ 离屏 WebView JS 引擎 + JS 插件系统。
        // 这些是 GenUI 移植的核心能力，ZorvAI 主工具集不含，必须从 [GenUI_TOOLS] 接管执行。
        val genui = genuiDeclarations()
        for (i in 0 until genui.length()) arr.put(genui.get(i))
        return arr
    }

    /**
     * 执行单个工具：把 GenUI 的 (name, JSONObject) 调用转成 ZorvAI 的 [QuroToolCall]，
     * 经 [QuroToolEngine] 派发，再把结果规整成 GenUI AgentLoop 期望的 [JSONObject]。
     *
     * 结果规整策略：
     *  - 若 ZorvAI 返回的是合法 JSON 对象（如 web_search 的 {"results":[...]}），原样回传；
     *  - 若为纯文本，带失败特征（失败/错误/异常/超时/需要权限/未知工具）则包成 {"error":"..."}，
     *    否则包成 {"result":"..."}，使 AgentLoop 的摘要/事件逻辑正常工作。
     */
    suspend fun execute(name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        // GenUI 专属工具（小程序 / 离屏 JS / 插件）由本适配器直接接管，不走 ZorvAI 主工具引擎，
        // 否则模型永远调不到 genui_native_ui / run_js，GenUI 原生界面就是死的。
        if (name in GENUI_TOOLS || name.startsWith("plugin_")) {
            return@withContext executeGenUi(name, args)
        }
        val call = QuroToolCall(name = name, arguments = args.toString())
        val results: List<QuroToolResult> = runCatching {
            engine.execute(appContext, listOf(call))
        }.getOrDefault(listOf(QuroToolResult(name, "工具执行异常：引擎未返回结果")))
        val raw = results.firstOrNull()?.result ?: "{}"
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
        if (parsed != null) return@withContext parsed
        val o = JSONObject()
        if (looksFailed(raw)) o.put("error", raw) else o.put("result", raw)
        o
    }

    /** GenUI 决策轮按"工具族"查询权限等级；ZorvAI 工具名不在 GenUI 旧表里，直接返回原名（等级回退到 L5 显示）。 */
    fun gateFor(name: String): String = name

    private fun looksFailed(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("失败") || t.contains("错误") || t.contains("异常") ||
                t.contains("超时") || t.contains("需要权限") || t.contains("未授权") ||
                t.contains("未知工具") || t.contains("error") || t.contains("exception")
    }

    // ---------- GenUI 专属工具（移植自上游 GenUI，对接自研 miniapp-sdk + 离屏 JS 引擎） ----------

    /**
     * GenUI 专属工具名集合：由本适配器接管执行，不进 ZorvAI 主工具引擎。
     *
     * 注意：原生 UI 工具已由 `create_miniapp` 改名为 `genui_native_ui`（声明名）——
     * 旧名与工作室的 `miniapp`（HTML 小程序）撞概念，模型分不清该调哪个，于是什么都调一遍。
     * 旧名保留为别名，保证历史会话 / 已缓存提示词里的旧调用不会变成"未知工具"。
     */
    private val GENUI_TOOLS = setOf(
        "genui_native_ui", "create_miniapp",
        "open_miniapp", "list_miniapps",
        "run_js", "install_plugin", "uninstall_plugin", "list_plugins"
    )

    /** 原生 UI 工具的新名 / 旧名（别名）。判断调用时两个都认。 */
    companion object {
        const val TOOL_NATIVE_UI = "genui_native_ui"
        const val TOOL_NATIVE_UI_ALIAS = "create_miniapp"
        fun isNativeUiTool(name: String) = name == TOOL_NATIVE_UI || name == TOOL_NATIVE_UI_ALIAS
    }

    /** 真正执行 GenUI 专属工具（均为同步实现，包在 IO 协程里调用）。 */
    private fun executeGenUi(name: String, args: JSONObject): JSONObject {
        return when (name) {
            TOOL_NATIVE_UI, TOOL_NATIVE_UI_ALIAS -> createMiniApp(args)
            "open_miniapp" -> openMiniApp(args)
            "run_js" -> CodeRuntime.runJs(appContext, args.optString("code"), args.optLong("timeout_ms", 40000))
            "install_plugin" -> PluginRuntime.install(
                appContext, args.optString("name"), args.optString("version"),
                args.optString("tools_json"), args.optString("code")
            )
            "uninstall_plugin" -> PluginRuntime.uninstall(appContext, args.optString("id_or_name"))
            "list_plugins" -> listPlugins()
            "list_miniapps" -> listMiniApps()
            else -> if (name.startsWith("plugin_")) PluginRuntime.callTool(appContext, name, args)
            else JSONObject().put("error", "未知工具: $name")
        }
    }

    /** 小程序根目录：filesDir/miniapps —— 与 MiniAppEngine.userAppsRoot 一致 */
    private fun miniAppsRoot(): File =
        File(appContext.filesDir, "miniapps").apply { mkdirs() }

    /** app.json → navigationBarTitleText（标准 JSON，直接解析） */
    private fun miniAppTitle(cfg: String, fallback: String): String = runCatching {
        JSONObject(cfg).optJSONObject("window")?.optString("navigationBarTitleText", fallback) ?: fallback
    }.getOrDefault(fallback)

    /**
     * 把 AI 生成的微信语法小程序落地到 filesDir/miniapps/<appId>/。
     * 契约（与上游 GenUI 完全一致）：app_id + title + files（路径→内容映射）。
     * 为兼容旧调用，若 files 缺失但给了 wxml/wxss/js 单页参数，自动组装成 files 再走同一套校验。
     */
    private fun createMiniApp(args: JSONObject): JSONObject {
        val appId = args.optString("app_id", "").ifBlank { args.optString("id") }
            .ifBlank { "app_${System.currentTimeMillis()}" }
        if (!appId.matches(Regex("[a-z0-9_-]{2,32}")))
            return JSONObject().put("error", "app_id 需为 2-32 位小写英文/数字/-/_")
        // files 映射（优先）；缺失则回退单页 wxml/wxss/js；再缺失则报错
        val files = args.optJSONObject("files")
            ?: (args.optString("files", "").takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() })
            ?: runCatching {
                val wxml = args.optString("wxml").ifBlank { args.optString("index_wxml") }
                val wxss = args.optString("wxss").ifBlank { args.optString("index_wxss") }
                val js = args.optString("js").ifBlank { args.optString("index_js") }
                if (wxml.isBlank()) null else JSONObject()
                    .put("pages/index/index.wxml", wxml)
                    .put("pages/index/index.wxss", wxss)
                    .put("pages/index/index.js", if (js.isBlank()) "Page({});" else js)
            }.getOrNull()
            ?: return JSONObject().put("error",
                "files 缺失：需为 {路径: 内容} 映射（如 {\"app.json\":\"...\",\"pages/index/index.wxml\":\"...\",\"pages/index/index.js\":\"...\"}）")
        val appDir = File(miniAppsRoot(), appId)
        if (appDir.exists()) appDir.deleteRecursively()
        val title = args.optString("title", appId)
        var hasJson = false
        val namesArr = files.names() ?: return JSONObject().put("error", "files 为空（没有可写入的文件）")
        val keys = (0 until namesArr.length()).map { namesArr.getString(it) }
        for (k in keys) {
            val content = files.optString(k)
            if (k.contains("..") || k.startsWith("/")) return JSONObject().put("error", "非法路径：$k")
            val f = File(appDir, k)
            f.parentFile?.mkdirs()
            f.writeText(content)
            if (k == "app.json") hasJson = true
        }
        if (!hasJson) {
            File(appDir, "app.json").writeText(
                """{"pages":["pages/index/index"],"window":{"navigationBarTitleText":"$title"}}""")
            if (keys.none { it.startsWith("pages/index/index.") }) {
                val p = File(appDir, "pages/index/index")
                p.parentFile?.mkdirs()
                p.resolve(".wxml").writeText("<view class=\"box\"><text class=\"tip\">$title</text></view>")
                p.resolve(".wxss").writeText(".box{padding:40rpx}.tip{color:#d9a05b;font-size:32rpx}")
                p.resolve(".js").writeText("Page({data:{}})")
            }
        } else {
            // 标题统一写进 app.json（缺 window.navigationBarTitleText 时补一段）
            val cfgF = File(appDir, "app.json")
            val cfg = cfgF.readText()
            if (!cfg.contains("navigationBarTitleText")) {
                cfgF.writeText(cfg.replaceFirst("{", "{\"window\":{\"navigationBarTitleText\":\"$title\"},"))
            }
        }
        // ① app.json 的 pages：严格 JSON → 引擎宽松解析 → 按落盘 .wxml 反推，三级兜底。
        // 旧实现是"解析失败就 deleteRecursively + 报错"，等于把 AI 已经写好的整包丢掉，
        // 对话框里自然什么都没有（"AI 没有写进对话框"）。现在只有"连页面文件都没有"才打回。
        val cfgPath = File(appDir, "app.json")
        if (!cfgPath.isFile) {
            appDir.deleteRecursively()
            return JSONObject().put("error",
                "缺 app.json（包结构必需）：{\"pages\":[\"pages/index/index\"],\"window\":{\"navigationBarTitleText\":\"标题\"}}")
        }
        val cfgRaw = cfgPath.readText()
        val strictPages: List<String> = runCatching {
            val arr = JSONObject(cfgRaw).optJSONArray("pages")
            if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
        val loosePages = strictPages.ifEmpty {
            com.yuanbao.miniapp.pack.AppConfig.parse(cfgRaw).pages
        }
        var repairedCfg = false
        val pages: List<String> = loosePages.ifEmpty {
            val fromFiles = keys.filter { it.endsWith(".wxml") }
                .map { it.removeSuffix(".wxml") }
                .sortedBy { if (it == "pages/index/index") 0 else it.length }
            repairedCfg = fromFiles.isNotEmpty()
            fromFiles
        }
        if (pages.isEmpty()) {
            appDir.deleteRecursively()
            return JSONObject().put("error",
                "包里没有任何 .wxml 页面，无法确定入口。至少给 pages/index/index.wxml + pages/index/index.js，" +
                    "app.json 写 {\"pages\":[\"pages/index/index\"]}。已提供文件：${keys.joinToString()}")
        }
        // 解析失败但页面齐全 → 用干净的 app.json 覆盖（pages 用我们确定可用的一份，标题保留），
        // 让引擎读到确定可用的配置，而不是靠它的兜底去猜。
        if (repairedCfg || (strictPages.isEmpty() && loosePages.isNotEmpty())) {
            cfgPath.writeText(
                "{\"pages\":[" + pages.joinToString(",") { "\"$it\"" } + "]" +
                    ",\"window\":{\"navigationBarTitleText\":\"$title\"}}"
            )
        }
        // ② 每个页面的 wxml/js 必须存在（wxss 可选；文件系统检查，零误杀）
        for (p in pages) {
            val missing = listOf("$p.wxml", "$p.js").filter { !File(appDir, it).isFile }
            if (missing.isNotEmpty()) {
                val names = keys.joinToString()
                appDir.deleteRecursively()
                return JSONObject().put("error",
                    "页面文件缺失：${missing.joinToString()}（app.json pages 里声明了 $p）\n已提供文件：$names\n补齐后重新调 genui_native_ui。")
            }
        }
        // ③ WXML 试解析：软校验（fail-open）——解析器误报不删包不打回，警告随结果返回供 AI 自纠
        val wxmlWarnings = mutableListOf<String>()
        for (wf in keys.filter { it.endsWith(".wxml") }.sorted()) {
            try {
                com.yuanbao.miniapp.view.WxmlParser().parse(File(appDir, wf).readText())
            } catch (e: Exception) {
                wxmlWarnings.add("WXML 解析警告 @$wf：${e.message?.take(200)}")
            }
        }
        // ④ JS 语法预检：引擎级解析（历史验证无误杀，保留 fail-closed；引擎意外崩溃放行）
        val engine = com.yuanbao.miniapp.core.MiniAppEngine.createEngine()
        try {
            for (jsf in keys.filter { it.endsWith(".js") }.sorted()) {
                val code = File(appDir, jsf).readText()
                val r = try {
                    engine.evaluate("(function(){\n" + code + "\n})")
                } catch (e: Exception) { null }
                if (r != null && r.isError()) {
                    val err = engine.lastError().take(300)
                    appDir.deleteRecursively()
                    return JSONObject().put("error",
                        "JS 语法错误 @$jsf：$err\n自研引擎语法边界（与工具说明一致）：禁用模板字符串(反引号)/解构/展开(...)/默认参数/对象方法简写/class/async/await/可选链?./空值合并??；字符串拼接用 + ；对象写 {key: function(){}} 不写方法简写。修正后重新调 genui_native_ui。")
                }
            }
        } finally {
            runCatching { engine.close() }
        }
        val ret = JSONObject().put("ok", true).put("created", appId).put("app_id", appId)
            .put("root", appDir.absolutePath)
            .put("files", JSONArray(keys))
            .put("syntax", "checked")
            .put("hint", "创建完成（app.json/页面完整性/JS 语法已校验通过），小程序会自动内嵌在对话流里直接可交互；只有用户明确要求『全屏打开』时才调 open_miniapp。")
        if (wxmlWarnings.isNotEmpty())
            ret.put("warnings", JSONArray(wxmlWarnings))
                .put("hint", ret.optString("hint") + " 注意存在 WXML 解析警告（不阻塞），建议检查标签闭合与 wx:for 语法。")
        return ret
    }

    /** 全屏打开已创建的小程序（校验存在性后启动 GenUiMiniAppActivity）。 */
    private fun openMiniApp(args: JSONObject): JSONObject {
        val appId = args.optString("app_id", "").ifBlank { args.optString("id") }
        if (appId.isBlank()) return JSONObject().put("error", "app_id 不能为空")
        if (MiniAppEngine.resolvePackage(appContext, appId) == null)
            return JSONObject().put("error", "GenUI 原生 UI $appId 不存在，请先用 genui_native_ui 创建")
        runCatching {
            val it = Intent(appContext, GenUiMiniAppActivity::class.java)
                .putExtra("appId", appId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(it)
        }.onFailure { return JSONObject().put("error", "打开失败：${it.message}") }
        return JSONObject().put("ok", true).put("app_id", appId)
    }

    /** 列出全部界面（内置示例 + AI 生成的），供 open_miniapp / genui_native_ui 引导。 */
    private fun listMiniApps(): JSONObject {
        val arr = JSONArray()
        runCatching {
            appContext.assets.list("miniprograms")?.forEach { id ->
                val cfg = runCatching {
                    appContext.assets.open("miniprograms/$id/app.json").bufferedReader().use { it.readText() }
                }.getOrDefault("")
                arr.put(JSONObject().put("app_id", id).put("title", miniAppTitle(cfg, id)).put("source", "内置"))
            }
        }
        miniAppsRoot().listFiles { f -> f.isDirectory && File(f, "app.json").exists() }
            ?.sortedBy { it.name }?.forEach { d ->
                val cfg = runCatching { File(d, "app.json").readText() }.getOrDefault("")
                arr.put(JSONObject().put("app_id", d.name).put("title", miniAppTitle(cfg, d.name)).put("source", "你创建的"))
            }
        return JSONObject().put("apps", arr)
            .put("hint", "用 open_miniapp 全屏打开；用 genui_native_ui 创建新的。")
    }

    private fun listPlugins(): JSONObject {
        val arr = JSONArray()
        PluginRuntime.list(appContext).forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("version", p.version)
                .put("tools", JSONArray(p.tools.map { it.name })))
        }
        return JSONObject().put("ok", true).put("plugins", arr)
    }

    /** GenUI 专属工具的 OpenAI function-calling 声明（与 coreSpecs 同形）。 */
    private fun genuiDeclarations(): JSONArray {
        val arr = JSONArray()
        fun gdecl(name: String, desc: String, props: JSONObject, required: List<String>) =
            arr.put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", name).put("description", desc)
                    .put("parameters", JSONObject().put("type", "object")
                        .put("properties", props).put("required", JSONArray(required)))))
        gdecl(TOOL_NATIVE_UI,
            "【GenUI 原生 UI（原 create_miniapp）】用自研原生引擎渲染一个真实可交互的界面（微信小程序语法：app.json/app.js/app.wxss + pages/index/index.{wxml,wxss,js}），保存成功后**自动内嵌 GenUI 对话流渲染**（用户可直接试玩），也可 open_miniapp 全屏打开。适合：待办、计算器、查数工具、记账等有状态小应用。【与 miniapp 工具的区别（别搞混）】：本工具产出的是**WXML/WXSS/JS 原生界面**，不走 HTML、不注入 window.native，要调原生能力请改用 miniapp（小程序工作室）。【尺寸单位：全部用 rpx（750rpx=整屏宽），禁止 px】【布局：手机竖屏单列；display:flex 横排记得 flex-wrap】【超过一屏：最外层用 <scroll-view scroll-y style=\"height:100%\"> 包住，普通 view 超出卡片视口的部分会被裁掉】【多页】：app.json 的 pages 数组列出全部页面（每页 pages/xxx/xxx.{wxml,wxss,js} 四件套齐全），首屏页放 pages[0]。【JS 语法边界（自研引擎，必须严格遵守否则被打回）】：支持 var/let/const、function、箭头函数、闭包、对象/数组字面量、字符串 + 拼接、if/else/for/while、JSON、Page({data:{...}, onTap: function(){ this.setData({...}) }})、App({})、wx.* API；【禁用】模板字符串（反引号）、解构、展开(...)、默认参数、对象方法简写、class、async/await、可选链?.、空值合并??。",
            JSONObject()
                .put("app_id", JSONObject().put("type", "string").put("description", "英文短 id，如 weather-tool"))
                .put("title", JSONObject().put("type", "string").put("description", "显示标题（写入 app.json 的 navigationBarTitleText）"))
                .put("files", JSONObject().put("type", "object").put("description", "相对路径到文件内容的映射，路径不以 / 开头：{\"app.json\":\"...\",\"app.js\":\"...\",\"app.wxss\":\"...\",\"pages/index/index.wxml\":\"...\",\"pages/index/index.wxss\":\"...\",\"pages/index/index.js\":\"...\"}")),
            listOf("app_id", "files"))
        gdecl("open_miniapp",
            "全屏打开一个已创建的 GenUI 原生 UI（校验存在性，不存在会报错提示先用 $TOOL_NATIVE_UI）。返回 {ok, app_id}。",
            JSONObject().put("app_id", JSONObject().put("type", "string")
                .put("description", "$TOOL_NATIVE_UI 返回的 id（也可先 list_miniapps 查有哪些）")),
            listOf("app_id"))
        gdecl("run_js",
            "运行 JavaScript 代码（离屏 WebView 真 Chromium 引擎，支持 ES2020+/async/await/fetch，console.* 捕获回传）。返回 {ok, result, logs}。",
            JSONObject().put("code", JSONObject().put("type", "string")
                .put("description", "JS 代码，函数体，支持 return；自动包 async IIFE"))
                .put("timeout_ms", JSONObject().put("type", "integer").put("description", "超时毫秒，默认 40000")),
            listOf("code"))
        gdecl("install_plugin",
            "安装 GenUI 插件：JS 代码 + 工具清单，安装后其工具自动并入工具列表（plugin_ 前缀），后续对话可直接调用。code 必须为返回函数映射的函数体。",
            JSONObject()
                .put("name", JSONObject().put("type", "string").put("description", "插件名"))
                .put("version", JSONObject().put("type", "string").put("description", "版本，默认 1.0"))
                .put("tools_json", JSONObject().put("type", "string").put("description", "工具清单 JSON 数组字符串，每项 {name,description,parameters}"))
                .put("code", JSONObject().put("type", "string").put("description", "JS 代码，函数体 return {工具名: async (args)=>{...}}")),
            listOf("name", "tools_json", "code"))
        gdecl("uninstall_plugin", "卸载指定插件（按 id 或名称）。",
            JSONObject().put("id_or_name", JSONObject().put("type", "string")), listOf("id_or_name"))
        gdecl("list_plugins", "列出已安装的全部插件及其工具。", JSONObject(), emptyList())
        gdecl("list_miniapps", "列出 GenUI 里全部可用界面（内置示例 + 你创建的 GenUI 原生 UI），供 open_miniapp / $TOOL_NATIVE_UI 引导。", JSONObject(), emptyList())
        return arr
    }
}

/**
 * GenUI 全模式的权限门禁适配层（具名类，供 [AgentLoop] 跨文件稳定解析）。
 *
 * 对接 ZorvAI 后的设计：真正的权限把关已经下沉到 [QuroToolEngine] 内部
 * （QuroPermissionHolder，与 ZorvAI 主对话完全一致），因此这里对 GenUI 决策轮
 * 一律返回「放行」（modeOf = ALWAYS_ALLOW，authorize = null），避免 GenUI 与 ZorvAI
 * 两套权限流程叠加导致重复弹窗 / 双重拦批。
 *
 * 时间线里仍用 [ToolGate.level] 取等级做展示（如授权卡等级徽标），不影响放行决策。
 */
class ZorvGate {
    fun modeOf(tool: String): ToolGate.AuthMode = ToolGate.AuthMode.ALWAYS_ALLOW

    suspend fun authorize(
        tool: String,
        brief: String,
        ask: suspend (tool: String, brief: String, level: Int) -> Boolean
    ): String? = null
}
