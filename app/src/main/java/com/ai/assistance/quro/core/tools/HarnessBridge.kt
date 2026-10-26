package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.mcp.QuroLocalMcpManager
import com.ai.assistance.quro.core.mcp.QuroMcpClientPrefs

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ZorvAI ⇄ DeepSeek Harness 的双向工具桥。
 *
 * ## 为什么是 MCP
 *
 * 两个 agent 各有自己的一套工具，谁也不想为对方重写一遍。MCP（Model Context Protocol）
 * 正好是这个形状的接口：harness 内置 `@deepseek-ai/dsh-mcp-client`，描述是
 * "connects to MCP servers and registers their tools on ctx.tools" —— 也就是说
 * **harness 会把远端 MCP server 的工具当成自己的工具用**，注册名是 `mcp__<serverName>__<tool>`。
 *
 * 于是方向一（harness 用 ZorvAI 的工具）只需要三件事：
 *   1. 把 ZorvAI 的每个 QuroTool 包装成一条 MCP 工具定义（`handler_type = quro_tool`）；
 *   2. 用已有的本地 MCP server 把它挂到 `http://127.0.0.1:<port>/mcp`；
 *   3. 给 harness 的**活 profile patch** 追加一个 insert 条目，让它启动时加载一个
 *      mcp-client 插件实例连过来。
 *
 * ## 互控多通道（2026-10-26 用户裁定：MCP 是一种，内置插件也是一种，多通道应该做出来）
 *
 * 方向一有**两条通道**，同一批工具、同一执行面（[QuroLocalMcpServer] 的两个协议面）：
 *
 *   · **MCP 通道**（运行时写入）：上述 patch + `@deepseek-ai/dsh-mcp-client`，
 *     经 `/mcp` JSON-RPC 面调用，harness 侧工具名 `mcp__zorvai__<工具名>`；
 *     patch 是启动期读的，**需重启 harness 生效**。
 *   · **内置插件通道**（随快照装配）：harness 侧内置插件
 *     `@dsh-android/dsh-android-zorvai-tools`（仓内 `plugins/dsh-android-zorvai-tools`，
 *     已登记进 inject 链与快照），经 `/tool/<工具名>` 纯 HTTP 面调用，
 *     harness 侧工具名 `zorvai_<工具名>`（元工具 `zorvai_bridge` 常驻可见，可自诊断/刷新）。
 *     壳侧只写一份**零配置端点清单** `<dshHome>/zorvai-tools.json`（端点 + 工具定义）：
 *     插件 apply 时读它注册工具、每次 execute 前重读——换端口无需重启引擎；
 *     清单缺席 = 桥未启用，插件只注册元工具，引擎照常启动（不炸 boot）。
 *
 * 两条通道调用同一批 ZorvAI 工具；一条失败换另一条即可，语义等价。
 *
 * ## patch 落点（#404 修正：旧路径已失效）
 *
 * 新引擎的 patch 链只读 `filesDir/home/.dsh/profiles/{web,headless}/cordis.patch.yml`
 * （快照事务 + FactoryProfilePatch.merge 的那条链）。旧实现写 `filesDir/dsh/dsh-bridge.patch.yml`
 * —— 新引擎**根本不读**那个文件，桥接静默失效。
 *
 * 写法是**标记块幂等追加**：只动 `# >>> zorvai-mcp-bridge` 与 `# <<< zorvai-mcp-bridge`
 * 之间的内容，工厂条目与用户改动一行不碰；insert 条目带稳定 id（`zorvai-mcp-bridge`），
 * 与仓内既有 insert 条目（shell-termux / android-bridge…）同一形态，快照刷新时的
 * mergePatchYamlById 能按 id 追踪。删掉标记块即完全回退。
 *
 * ## 方向二（ZorvAI 用 harness 的工具与插件）
 *
 * harness 没有 MCP server，无法把自己注册出去；它能对外的通道是 RPC 派任务
 * （HarnessTool action=task）。所以这一侧是「把活交给它，让它用自己的插件干」，
 * 而 [capabilities] 负责把它的插件清单摊开给 ZorvAI 的 AI 看 —— 知道对方有什么，
 * 才谈得上「操控它的完整工具」。
 */
object HarnessBridge {

    private const val TAG = "HarnessBridge"

    /** 暴露给 harness 的 MCP 服务器别名（会出现在工具名 `mcp__zorvai__*` 里）。 */
    const val ALIAS = "zorvai"

    /** insert 条目 id（稳定标识：快照 merge 与幂等重写都按它认）。 */
    private const val ENTRY_ID = "zorvai-mcp-bridge"

    /** harness 的 mcp-client 插件包名。 */
    private const val MCP_CLIENT_PKG = "@deepseek-ai/dsh-mcp-client"

    /** 内置插件通道的端点清单文件名（落在 <dshHome> 根下）。 */
    private const val MANIFEST_FILE = "zorvai-tools.json"

    /** 内置插件通道的 HTTP 工具面路径（与 QuroLocalMcpServer 的 TOOL_ROUTE_PREFIX 对应）。 */
    private const val TOOL_PATH = "/tool"

    private const val BLOCK_BEGIN = "# >>> zorvai-mcp-bridge (managed by ZorvAI; do not edit)"
    private const val BLOCK_END = "# <<< zorvai-mcp-bridge"

    /**
     * 活 profile patch（web profile）：新引擎唯一会读的 patch 层。
     *
     * 注意这是**设备侧 live 文件**（快照解压后由 SnapshotTransaction 落盘）；
     * 快照未解压前它不存在——那时 [enable] 会如实要求先起引擎。
     */
    fun patchFile(context: Context): File =
        File(File(File(File(context.filesDir, "home"), ".dsh"), "profiles"), "web/cordis.patch.yml")

    /**
     * 内置插件通道的端点清单：`filesDir/home/.dsh/zorvai-tools.json`。
     *
     * 由 [enable] 写、[disable] 删；插件每次 execute 前重读它，所以端口刷新后由
     * [refreshManifest] 重写即生效，无需重启引擎。
     */
    fun manifestFile(context: Context): File =
        File(File(File(context.filesDir, "home"), ".dsh"), MANIFEST_FILE)

    fun isEnabled(context: Context): Boolean {
        val f = patchFile(context)
        return f.isFile && f.readText().contains(ENTRY_ID)
    }

    /**
     * 用户是否**显式**关闭过互控桥（设置里的 bridge_off）。
     *
     * 为什么需要这个标记：桥原本只在「引擎每次 spawn 前」建一次，而 spawn 是无条件
     * `enable()` 的 —— 于是「关闭」会被下一次启动静默复活的另一面是：**引擎一次都没成功
     * 起来过时，桥就一次都没建过**（用户实测：端点清单不存在、127.0.0.1 上没有 /tool 监听）。
     * 现在应用启动即自愈（[ensureAuto]），但必须尊重用户显式关闭，否则关掉又自己活了。
     */
    private const val PREF_USER_OFF = "harness_bridge_user_off"

    private fun prefs(context: Context) =
        context.getSharedPreferences("quro_ui", Context.MODE_PRIVATE)

    fun isUserDisabled(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(PREF_USER_OFF, false) }.getOrDefault(false)

    /**
     * 把 ZorvAI 的全部工具暴露给 harness（**显式**启用：用户点 bridge_on 或设置里打开）。
     *
     * 返回给人看的说明（含 endpoint 与工具数）。失败时返回以「⛔」开头的原因。
     */
    fun enable(context: Context): String {
        runCatching { prefs(context).edit().putBoolean(PREF_USER_OFF, false).apply() }
        return enableInternal(context)
    }

    /**
     * 自动启用（应用启动 / 引擎 spawn 前调用）：幂等；用户显式关闭过则跳过。
     *
     * 与 [enable] 的唯一区别就是这条「尊重关闭」判断。
     */
    fun ensureAuto(context: Context): String {
        if (isUserDisabled(context)) return "用户已关闭互控桥，跳过自动启用"
        return enableInternal(context)
    }

    private fun enableInternal(context: Context): String {
        // 🔴 注册表构建异常必须说人话：此前是裸抛，被上层 runCatching 吞掉 → 桥静默缺失
        val reg = runCatching { buildQuroRegistry(context) }
            .getOrElse { return "⛔ 构建 ZorvAI 工具注册表失败：${it.message ?: it.javaClass.simpleName}" }
        val tools = runCatching { reg.all() }
            .getOrElse { return "⛔ 枚举 ZorvAI 工具失败：${it.message ?: it.javaClass.simpleName}" }
        if (tools.isEmpty()) return "⛔ ZorvAI 当前没有可暴露的工具"

        val defs = JSONArray()
        var skipped = 0
        tools.forEach { t ->
            val n = t.name.trim()
            // MCP 工具名有字符集限制，非法名直接跳过并计数（不要把它们弄成静默丢失）
            if (n.isEmpty() || !n.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                skipped++
            } else {
                defs.put(JSONObject().apply {
                    put("name", n)
                    put("description", t.description.take(600))
                    put(
                        "parameters",
                        runCatching { JSONObject(t.parametersJson) }
                            .getOrElse { JSONObject().put("type", "object") }
                    )
                    put("handler_type", "quro_tool")
                    put("handler_config", JSONObject().put("tool", n))
                })
            }
        }
        if (defs.length() == 0) return "⛔ 没有名称合法的工具可供暴露"

        val deployMsg = QuroLocalMcpManager.deploy(context, ALIAS, defs.toString())
        if (deployMsg.startsWith("⛔") || deployMsg.contains("失败")) {
            return "⛔ 部署本地 MCP 失败：$deployMsg"
        }
        val cfg = QuroMcpClientPrefs.loadLocal(context).firstOrNull { it.alias == ALIAS }
            ?: return "⛔ 已部署但读不到配置：$deployMsg"
        val url = cfg.url
        if (url.isBlank()) return "⛔ 本地 MCP 没有 endpoint"

        // ══ ② 内置插件通道：**先**写端点清单 ══
        // 🔴 顺序是有讲究的（2026-10-26 真机事故）：清单只依赖「MCP server 已起」，
        // 而 profile patch 要等快照解压后才存在。旧实现把清单写在 patch 之后，
        // 首次启动时 patch 还没落盘 → 直接 ⛔ 返回 → 清单永远不写，
        // 插件只注册元工具 zorvai_bridge，`zorvai_*` 一个都没有（用户实测：
        // zorvai-tools.json 不存在、127.0.0.1 上也没有 /tool 监听）。
        // 现在清单先写：引擎起来的那一刻通道②就已经可用，不必等下一次重启。
        val manifestErr = writeManifest(context, defs.toString(), url)

        // ══ ① MCP 通道：profile patch（启动期读取，需重启引擎生效）══
        val f = patchFile(context)
        if (!f.isFile) {
            // 降级而不是全废：快照还没解压时 patch 不存在，这是**正常时序**，不是错误。
            // 每次 spawn 前 ensureBridge 都会再调一次，patch 落盘后自动补写。
            return buildString {
                if (manifestErr == null) {
                    appendLine("✅ 内置插件通道已就绪：${defs.length()} 个工具 → $url$TOOL_PATH")
                    appendLine("   清单：${manifestFile(context).absolutePath}")
                    appendLine("   harness 侧工具名：zorvai_<工具名>（元工具 zorvai_bridge 可自诊断/刷新）")
                } else {
                    appendLine("⚠ 端点清单写入失败：$manifestErr")
                }
                appendLine()
                appendLine("⏳ MCP 通道待接：找不到 harness 的 profile patch（${f.absolutePath}）。")
                appendLine("   快照尚未解压时这是正常时序 —— 下次启动 harness 会自动补写，无需手动操作。")
            }
        }
        val block = buildString {
            appendLine(BLOCK_BEGIN)
            appendLine("# 由 ZorvAI 生成：把 ZorvAI 的完整工具集挂进 harness（同一环境，双向互控）。")
            appendLine("# 删掉本标记块即可完全回退（harness 不再加载这个 MCP 客户端）。")
            appendLine("- insert:")
            appendLine("    - id: $ENTRY_ID")
            appendLine("      name: '$MCP_CLIENT_PKG'")
            appendLine("      config:")
            appendLine("        transport: streamable-http")
            appendLine("        url: '$url'")
            appendLine("        serverName: '$ALIAS'")
            appendLine(BLOCK_END)
        }
        val merged = try {
            stripManagedBlock(f.readText()) + block
        } catch (t: Throwable) {
            return "⛔ 读写 profile patch 失败：${t.message ?: t.javaClass.simpleName}"
        }
        // 原子替换：引擎随时可能读这个文件，不能留半截
        val tmp = File(f.parentFile, f.name + ".zorvai.tmp")
        try {
            tmp.writeText(merged)
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            tmp.delete()
            return "⛔ 写入 profile patch 失败：${t.message ?: t.javaClass.simpleName}"
        }
        Log.i(TAG, "已桥接 ${defs.length()} 个工具 → $url（patch=${f.absolutePath}）")

        return buildString {
            appendLine("✅ 已把 ZorvAI 的 ${defs.length()} 个工具暴露给 DeepSeek Harness（多通道）")
            if (skipped > 0) appendLine("   （$skipped 个名称不合法的工具已跳过）")
            appendLine()
            appendLine("① MCP 通道（patch 启动期读取，需重启 harness 生效）")
            appendLine("   端点：$url")
            appendLine("   桥接层：${f.absolutePath}")
            appendLine("   harness 侧工具名：mcp__${ALIAS}__<工具名>")
            appendLine()
            appendLine("② 内置插件通道（随快照装配，清单落盘即生效）")
            if (manifestErr == null) {
                appendLine("   清单：${manifestFile(context).absolutePath}")
                appendLine("   harness 侧工具名：zorvai_<工具名>（元工具 zorvai_bridge 可自诊断/刷新）")
            } else {
                appendLine("   ⚠ 端点清单写入失败：$manifestErr")
                appendLine("   （内置插件通道暂不可用；MCP 通道不受影响）")
            }
            appendLine()
            appendLine("两条通道调用同一批 ZorvAI 工具，语义等价；一条失败换另一条重试即可。")
            appendLine("⚠ MCP 通道需要重启 harness 才生效（harness action=restart，或 stop+start）。")
        }
    }

    /** 关闭桥接：删 patch 标记块 + 删端点清单 + 注销本地 MCP + 记下「用户显式关闭」。 */
    fun disable(context: Context): String {
        // 记下显式关闭：否则应用启动自愈 / 下次 spawn 会立刻把它复活
        runCatching { prefs(context).edit().putBoolean(PREF_USER_OFF, true).apply() }
        val f = patchFile(context)
        var existed = false
        if (f.isFile) {
            existed = runCatching { f.readText().contains(ENTRY_ID) }.getOrDefault(false)
            if (existed) {
                val stripped = runCatching { stripManagedBlock(f.readText()) }
                    .getOrElse { return "⛔ 读取 profile patch 失败：${it.message}" }
                val tmp = File(f.parentFile, f.name + ".zorvai.tmp")
                try {
                    tmp.writeText(stripped)
                    if (!tmp.renameTo(f)) {
                        tmp.copyTo(f, overwrite = true)
                        tmp.delete()
                    }
                } catch (t: Throwable) {
                    tmp.delete()
                    return "⛔ 写回 profile patch 失败：${t.message ?: t.javaClass.simpleName}"
                }
            }
        }
        val msg = runCatching { QuroLocalMcpManager.undeploy(context, ALIAS) }
            .getOrElse { "注销本地 MCP 失败：${it.message}" }
        // 内置插件通道：删端点清单（缺席即插件只注册元工具 zorvai_bridge，天然回退）
        val mf = manifestFile(context)
        val manifestExisted = mf.isFile
        if (manifestExisted) {
            runCatching { mf.delete() }
                .onFailure { Log.w(TAG, "删端点清单失败：${it.message}") }
        }
        return buildString {
            appendLine(if (existed) "✅ 已移除 harness 桥接层" else "桥接层本就不存在")
            appendLine(msg)
            if (manifestExisted) appendLine("已删除内置插件通道端点清单（$MANIFEST_FILE）")
            appendLine("⚠ 需重启 harness 才生效。")
        }
    }

    fun status(context: Context): String = buildString {
        val f = patchFile(context)
        appendLine("=== ZorvAI ⇄ DeepSeek Harness 桥接状态 ===")
        appendLine("桥接层文件 : " + if (f.isFile && f.readText().contains(ENTRY_ID)) "✓ ${f.absolutePath}" else "✗ 未启用")
        if (f.isFile && f.readText().contains(ENTRY_ID)) {
            appendLine("桥接条目（harness 重启后生效）：")
            f.readText().lines().dropWhile { !it.contains(BLOCK_BEGIN) }
                .takeWhile { !it.contains(BLOCK_END) }
                .forEach { appendLine("   $it") }
            appendLine()
        }
        val cfg = QuroMcpClientPrefs.loadLocal(context).firstOrNull { it.alias == ALIAS }
        appendLine("本地 MCP   : " + if (cfg != null) "✓ ${cfg.url}" else "✗ 未部署")
        val mf = manifestFile(context)
        appendLine("插件清单   : " + if (mf.isFile) "✓ ${mf.absolutePath}" else "✗ 未启用")
        val count = buildQuroRegistry(context).all().size
        appendLine("可暴露工具 : $count 个")
        appendLine()
        appendLine("方向一（harness 用 ZorvAI 工具）—— 两条通道，同一批工具：")
        appendLine("  ① MCP 通道：mcp__${ALIAS}__<工具名>（patch 启动期读取，enable 后需重启 harness）")
        appendLine("  ② 内置插件通道：zorvai_<工具名>（随快照装配，清单在场即生效；")
        appendLine("     元工具 zorvai_bridge 可自诊断桥状态、refresh=true 补注册新工具）")
        appendLine()
        appendLine("方向二（ZorvAI 用 harness 工具/插件）：harness action=task 驱动它用自己的插件干活；")
        appendLine("                                    action=plugins 可列它的插件清单")
    }

    /** 剥掉本桥接写的标记块（含首尾标记行），其余内容原样保留。 */
    private fun stripManagedBlock(text: String): String {
        val lines = text.lines()
        val out = StringBuilder()
        var inBlock = false
        for (line in lines) {
            if (line.trim() == BLOCK_BEGIN) { inBlock = true; continue }
            if (line.trim() == BLOCK_END) { inBlock = false; continue }
            if (!inBlock) out.appendLine(line)
        }
        // 去掉因剥块产生的尾部空行堆积，最多保留一个换尾
        return out.toString().trimEnd('\n', '\r', ' ', '\t') + "\n"
    }

    /**
     * 写内置插件通道的端点清单（原子写；插件可能随时读它，不能留半截）。
     *
     * @param defsJson [enable] 里 build 出来的 MCP 工具定义数组（name/description/parameters）。
     * @param mcpUrl   本地 MCP 端点（`http://127.0.0.1:<port>/mcp`；清单里去掉 `/mcp`
     *                 后缀作 baseUrl——两个协议面同源不同路径）。
     * @return null = 成功；否则为人看的失败原因。
     */
    private fun writeManifest(context: Context, defsJson: String, mcpUrl: String): String? {
        val defs = runCatching { JSONArray(defsJson) }
            .getOrElse { return "工具定义不可序列化：${it.message}" }
        val tools = JSONArray()
        for (i in 0 until defs.length()) {
            val d = defs.optJSONObject(i) ?: continue
            tools.put(JSONObject().apply {
                put("name", d.optString("name", ""))
                put("description", d.optString("description", ""))
                put("parameters", d.opt("parameters") ?: JSONObject().put("type", "object"))
            })
        }
        val baseUrl = mcpUrl.trim().trimEnd('/').removeSuffix("/mcp").trimEnd('/')
        if (baseUrl.isBlank()) return "本地 MCP 端点为空"
        val manifest = JSONObject().apply {
            put("version", 1)
            put("alias", ALIAS)
            put("baseUrl", baseUrl)
            put("toolPath", TOOL_PATH)
            put("updatedAt", java.time.Instant.now().toString())
            put("tools", tools)
        }
        val mf = manifestFile(context)
        val tmp = File(mf.parentFile, mf.name + ".zorvai.tmp")
        return try {
            mf.parentFile?.mkdirs()
            tmp.writeText(manifest.toString())
            if (!tmp.renameTo(mf)) {
                tmp.copyTo(mf, overwrite = true)
                tmp.delete()
            }
            Log.i(TAG, "已写内置插件通道端点清单：${mf.absolutePath}（${tools.length()} 个工具 → $baseUrl$TOOL_PATH）")
            null
        } catch (t: Throwable) {
            tmp.delete()
            "写入端点清单失败：${t.message ?: t.javaClass.simpleName}"
        }
    }

    /**
     * 端口刷新后重写端点清单（[QuroLocalMcpManager.startAll] 应用重启恢复本地 MCP 时调用）。
     *
     * 本地 server 每次启动都换随机端口，manifest 里的一次性 baseUrl 会变成死端口；
     * 插件每次 execute 前重读清单，所以这里重写即生效，无需重启引擎。
     *
     * 桥未启用（patch 无标记块 / prefs 无 local 配置）时是 no-op——禁用态不写清单。
     */
    fun refreshManifest(context: Context) {
        // 桥未启用时是 no-op；但「patch 尚未落盘（快照没解压）、清单已存在」这一态也要刷新——
        // 通道② 现在先于 ① 上线（见 enable 里的顺序注释），不能因为 isEnabled=false 就停更端口。
        if (!isEnabled(context) && !manifestFile(context).isFile) return
        val cfg = QuroMcpClientPrefs.loadLocal(context).firstOrNull { it.alias == ALIAS } ?: return
        if (cfg.url.isBlank() || cfg.toolDefs.isBlank()) return
        val err = writeManifest(context, cfg.toolDefs, cfg.url)
        if (err != null) Log.w(TAG, "刷新端点清单失败：$err")
    }

    /**
     * 枚举 harness 的插件与能力（读它的包清单，不需要 harness 正在运行）。
     *
     * 这是方向二的「知情权」：ZorvAI 的 AI 只有知道 harness 装了哪些插件、各自干什么，
     * 才能在 task 里准确地要求它「用某个插件」做某件事。
     *
     * 路径随 dshmobile 化改为引擎树：`filesDir/usr/lib/node_modules/@deepseek-ai/dsh/node_modules`
     * （旧路径 `filesDir/dsh/dsh/node_modules` 已不存在）。
     */
    fun capabilities(context: Context): String {
        val nm = File(File(context.filesDir, "usr/lib/node_modules/@deepseek-ai/dsh"), "node_modules")
        if (!nm.isDirectory) return "harness 运行时未安装，先执行 harness(action=start)"
        val out = StringBuilder()
        var total = 0
        val groups = linkedMapOf<String, MutableList<String>>()
        nm.listFiles()?.forEach { top ->
            val pkgs: List<File> = if (top.name.startsWith("@")) {
                top.listFiles()?.filter { it.isDirectory } ?: emptyList()
            } else {
                listOf(top)
            }
            pkgs.forEach { p ->
                val pj = File(p, "package.json")
                if (!pj.isFile) return@forEach
                val text = runCatching { pj.readText() }.getOrNull() ?: return@forEach
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: return@forEach
                val pkgName = obj.optString("name", "")
                if (pkgName.isBlank()) return@forEach
                total++
                val desc = obj.optString("description", "").replace("\n", " ").take(180)
                val scope = if (pkgName.startsWith("@deepseek-ai/")) "deepseek" else "third-party"
                groups.getOrPut(scope) { mutableListOf() }
                    .add("  · $pkgName\n      $desc")
            }
        }
        out.appendLine("DeepSeek Harness 已装包：$total 个")
        out.appendLine()
        groups["deepseek"]?.let {
            out.appendLine("=== 官方包（${it.size}）===")
            it.sorted().take(120).forEach { line -> out.appendLine(line) }
        }
        groups["third-party"]?.let {
            out.appendLine()
            out.appendLine("=== 第三方依赖（${it.size}）===")
            it.sorted().take(40).forEach { line -> out.appendLine(line) }
        }
        out.appendLine()
        out.appendLine("提示：这些包就是 harness 的插件/能力来源。要让它在某方面干活，")
        out.appendLine("用 harness(action=task, task=...) 直接提要求即可 —— 它会自己挑插件。")
        return out.toString()
    }
}
