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

    fun isEnabled(context: Context): Boolean {
        val f = patchFile(context)
        return f.isFile && f.readText().contains(ENTRY_ID)
    }

    /**
     * 把 ZorvAI 的全部工具暴露给 harness。
     *
     * 返回给人看的说明（含 endpoint 与工具数）。失败时返回以「⛔」开头的原因。
     */
    fun enable(context: Context): String {
        val reg = buildQuroRegistry(context)
        val tools = reg.all()
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

        // 写 harness 的活 profile patch（幂等标记块，不动工厂条目）
        val f = patchFile(context)
        if (!f.isFile) {
            return "⛔ 找不到 harness 的 profile patch（${f.absolutePath}）。\n" +
                "快照可能还没解压——先执行 harness(action=start) 把引擎拉起来，再 bridge_on。"
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
            appendLine("✅ 已把 ZorvAI 的 ${defs.length()} 个工具暴露给 DeepSeek Harness")
            if (skipped > 0) appendLine("   （$skipped 个名称不合法的工具已跳过）")
            appendLine("   MCP 端点：$url")
            appendLine("   桥接层：${f.absolutePath}")
            appendLine()
            appendLine("harness 侧看到的工具名是 mcp__${ALIAS}__<工具名>，")
            appendLine("它自己的 agent 会像用内置工具一样调用它们（真正执行的是 ZorvAI 的工具）。")
            appendLine()
            appendLine("⚠ 需要重启 harness 才生效（harness action=restart，或 stop+start）。")
        }
    }

    /** 关闭桥接：删 patch 标记块 + 注销本地 MCP。 */
    fun disable(context: Context): String {
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
        return buildString {
            appendLine(if (existed) "✅ 已移除 harness 桥接层" else "桥接层本就不存在")
            appendLine(msg)
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
        val count = buildQuroRegistry(context).all().size
        appendLine("可暴露工具 : $count 个")
        appendLine()
        appendLine("方向一（harness 用 ZorvAI 工具）：MCP，需 enable 后重启 harness")
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
