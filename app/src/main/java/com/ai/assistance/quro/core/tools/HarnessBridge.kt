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
 * 正好是这个形状的接口：harness 内置 `dsh-mcp-client`，描述是 "connects to MCP servers
 * and registers their tools on ctx.tools" —— 也就是说 **harness 会把远端 MCP server 的工具
 * 当成自己的工具用**，注册名是 `mcp__<serverName>__<tool>`。
 *
 * 于是方向一（harness 用 ZorvAI 的工具）只需要三件事：
 *   1. 把 ZorvAI 的每个 QuroTool 包装成一条 MCP 工具定义（`handler_type = quro_tool`）；
 *   2. 用已有的本地 MCP server 把它挂到 `http://127.0.0.1:<port>/mcp`；
 *   3. 给 harness 写一层 patch，让它在启动时加载一个 mcp-client 插件实例连过来。
 *
 * ## patch 为什么可行
 *
 * dsh 的启动器把配置组织成「profile 层 + bundle 层 + 用户 patch 层」，patch 是一个
 * 顶层 YAML 数组，支持按 id 定向覆盖，也支持 `insert` 往条目列表里插新条目。
 * 这里用一个不带 id 的 `insert`，把 mcp-client 插件实例追加到顶层 —— 不动 harness 自己的
 * 任何配置，删掉这个文件就完全回退。
 *
 * ## 方向二（ZorvAI 用 harness 的工具与插件）
 *
 * harness 没有 MCP server，无法把自己注册出去；它能对外的通道是 headless（一次性任务）
 * 与 acp（JSON-RPC stdio）。所以这一侧是「把活交给它，让它用自己的插件干」，
 * 而 [capabilities] 负责把它的插件清单摊开给 ZorvAI 的 AI 看 —— 知道对方有什么，
 * 才谈得上「操控它的完整工具」。
 */
object HarnessBridge {

    private const val TAG = "HarnessBridge"

    /** 暴露给 harness 的 MCP 服务器别名（会出现在工具名 `mcp__zorvai__*` 里）。 */
    const val ALIAS = "zorvai"

    /** 生成的 harness patch 文件名（放在 harness 运行时根目录下）。 */
    const val PATCH_NAME = "dsh-bridge.patch.yml"

    /** harness 的 mcp-client 插件包名。 */
    private const val MCP_CLIENT_PKG = "@deepseek-ai/dsh-mcp-client"

    fun patchFile(context: Context): File =
        File(File(context.filesDir, "dsh"), PATCH_NAME)

    fun isEnabled(context: Context): Boolean = patchFile(context).isFile

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

        // 写 harness 的 patch 层
        val f = patchFile(context)
        f.parentFile?.mkdirs()
        f.writeText(
            """
            # 由 ZorvAI 生成：把 ZorvAI 的完整工具集挂进 harness。
            # 删掉本文件即可完全回退（harness 不再加载这个 MCP 客户端）。
            - insert:
                - name: '$MCP_CLIENT_PKG'
                  config:
                    transport: streamable-http
                    url: '$url'
                    serverName: '$ALIAS'
            """.trimIndent() + "\n"
        )
        Log.i(TAG, "已桥接 ${defs.length()} 个工具 → $url")
        return buildString {
            appendLine("✅ 已把 ZorvAI 的 ${defs.length()} 个工具暴露给 DeepSeek Harness")
            if (skipped > 0) appendLine("   （$skipped 个名称不合法的工具已跳过）")
            appendLine("   MCP 端点：$url")
            appendLine("   桥接层：${f.absolutePath}")
            appendLine()
            appendLine("harness 侧看到的工具名是 mcp__${ALIAS}__<工具名>，")
            appendLine("它自己的 agent 会像用内置工具一样调用它们（真正执行的是 ZorvAI 的工具）。")
            appendLine()
            appendLine("⚠ 需要重启 harness 才生效（先 stop 再 start，或用 restart）。")
        }
    }

    /** 关闭桥接：删 patch 层 + 注销本地 MCP。 */
    fun disable(context: Context): String {
        val f = patchFile(context)
        val existed = f.isFile
        if (existed) f.delete()
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
        appendLine("桥接层文件 : " + if (f.isFile) "✓ ${f.absolutePath}" else "✗ 未启用")
        if (f.isFile) {
            appendLine("内容：")
            appendLine(f.readText().lines().joinToString("\n") { "   $it" })
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

    /**
     * 枚举 harness 的插件与能力（读它的包清单，不需要 harness 正在运行）。
     *
     * 这是方向二的「知情权」：ZorvAI 的 AI 只有知道 harness 装了哪些插件、各自干什么，
     * 才能在 task 里准确地要求它「用某个插件」做某件事。
     */
    fun capabilities(context: Context): String {
        val nm = File(File(context.filesDir, "dsh"), "dsh/node_modules")
        if (!nm.isDirectory) return "harness 运行时未安装，先执行 setup"
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
                val name = pj.name
                if (name != "package.json" || !p.isDirectory) return@forEach
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
