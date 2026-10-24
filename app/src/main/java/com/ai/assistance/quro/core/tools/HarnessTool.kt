package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.terminal.harness.DeepSeekHarness
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * DeepSeek Harness（dsh）工具。
 *
 * 两条使用路径，对应两种真实需求：
 *
 *  1. **给人用**：`start` 起服务 → 用户在它自带的完整界面里干活（会话、插件、设置都是它自己的）；
 *  2. **给 AI 用**：`task` 走 harness 的 headless profile —— 直接跑 core Agent，
 *     答完一个任务就退出，答案走 stdout。这样 ZorvAI 可以把「需要一整套 agent 工作流」的
 *     活儿外包给 harness，而不用自己拼。
 *
 * harness 与 ZorvAI 是两个独立的 agent，各自有自己的模型配置与凭据：
 * 这里只负责把它拉起来、把话递进去、把答案带回来。
 */
class HarnessTool : QuroTool {
    override val name: String = "harness"

    override val description: String =
        "DeepSeek Harness（dsh）—— DeepSeek 官方的 agent harness，是一个独立完整的产品：" +
            "自带 Node 运行时、自带 agent 引擎、自带 Web 界面（会话/插件/设置/主题全在它自己 UI 里）。" +
            "ZorvAI 内置了它的完整运行时（Node 26 bionic 构建 + dsh 0.2.1-rc.2 全量插件包，约 240MB 资产），" +
            "不需要 Ubuntu、不需要联网安装、不经终端。用法：" +
            "setup 首次解压运行时；start 起 Web 服务（127.0.0.1:3080，UI 由 harness 自己提供）；" +
            "**task 是最重要的动作** —— 把一句话任务交给 harness 的 headless profile 直接执行，" +
            "它自己跑完整的 agent 循环（读文件、跑命令、改代码）然后把答案返回，" +
            "适合「这件活儿需要一整套 agent 工作流」的场景；" +
            "status/logs/diag 用于排障；set_key 存模型凭据。" +
            "注意：harness 需要自己的模型 API key（DEEPSEEK_API_KEY 等），与 ZorvAI 的模型配置互不相干。" +
            "**双向工具互通**：bridge_on 把 ZorvAI 的全部工具经 MCP 暴露给 harness" +
            "（它会以 mcp__zorvai__<工具名> 的形式当成自己的工具调用，执行的是真正的 ZorvAI 工具）；" +
            "bridge_off 撤销，bridge_status 看状态。" +
            "反方向由 task 承担：ZorvAI 把活交给 harness，由它用自己的插件与工具完成；" +
            "plugins 可列出 harness 已装的全部包（也就是它的能力来源），" +
            "知道它有什么才能在 task 里准确要求它用哪个能力。"

    override val parametersJson: String = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"操作","enum":["setup","start","stop","restart","status","logs","url","task","set_key","diag","bridge_on","bridge_off","bridge_status","plugins"]},
            "task":{"type":"string","description":"task 动作要交给 harness 执行的任务文本（一句话描述它要做什么，它会自己规划并执行）"},
            "session_id":{"type":"string","description":"task 时可选：续用某个已持久化的会话 id，让多轮任务有上下文"},
            "json":{"type":"boolean","description":"task 时可选：输出逐行 NDJSON 运行事件（想看中间步骤就设 true）"},
            "port":{"type":"number","description":"start 时的端口，默认 3080"},
            "provider":{"type":"string","description":"set_key 的厂商：deepseek（默认）/ openai / anthropic"},
            "key":{"type":"string","description":"set_key 时要保存的 API key"},
            "timeout_seconds":{"type":"number","description":"task 的超时秒数，默认 600；agent 干活可能很慢，别设太小"},
            "tail":{"type":"number","description":"logs 返回的尾部行数，默认 80"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val action = args.optString("action", "").trim().lowercase()
        if (action.isBlank()) {
            return "缺少 action。可用：setup/start/stop/restart/status/logs/url/task/set_key/diag"
        }
        val h = DeepSeekHarness.get(context)
        val timeoutMs = (args.optDouble("timeout_seconds", 600.0).toLong()).coerceIn(30, 3600) * 1000L

        return when (action) {
            "setup" -> {
                val steps = StringBuilder()
                val r = runBlocking { h.ensureInstalled { steps.appendLine(it) } }
                r.fold(
                    onSuccess = {
                        "DeepSeek Harness 运行时已就绪\n" + steps.toString().trim() + "\n\n" +
                            runBlocking { h.diagnose() }
                    },
                    onFailure = {
                        "安装失败：" + (it.message ?: it.javaClass.simpleName) +
                            "\n\n" + runBlocking { h.diagnose() }
                    }
                )
            }

            "start" -> {
                val port = args.optInt("port", DeepSeekHarness.DEFAULT_PORT)
                val r = runBlocking { h.start(port) }
                r.fold(
                    onSuccess = {
                        "DeepSeek Harness 已启动\n" +
                            "  地址: http://127.0.0.1:$it\n" +
                            "  数据: " + h.home.absolutePath + "\n" +
                            "  工作区: " + h.workspace.absolutePath + "\n" +
                            "界面由 harness 自己提供（用户可在其中配置模型、插件、会话）。"
                    },
                    onFailure = { "启动失败：" + (it.message ?: it.javaClass.simpleName) }
                )
            }

            "stop" -> {
                h.stop()
                "已停止 DeepSeek Harness"
            }

            "restart" -> {
                val port = args.optInt("port", h.activePort())
                val r = runBlocking { h.restart(port) }
                r.fold(
                    { "已重启，端口 $it" },
                    { "重启失败：" + (it.message ?: it.javaClass.simpleName) }
                )
            }

            "status" -> buildString {
                appendLine("DeepSeek Harness 状态")
                appendLine("  运行时: " + if (h.isInstalled()) "已安装" else "未安装（用 setup）")
                appendLine("  进程  : " + if (h.isRunning()) "运行中，端口 " + h.activePort() else "未运行")
                appendLine("  地址  : " + h.baseUrl())
                appendLine("  凭据  : " + listOf("deepseek", "openai", "anthropic")
                    .joinToString(" / ") { p ->
                        if (h.apiKey(p).isNotBlank()) "$p 已配置" else "$p 未配置"
                    })
                appendLine()
                appendLine("说明：界面与会话都由 harness 自己管理；ZorvAI 只负责起停。")
            }

            "url" -> if (h.isRunning()) {
                h.baseUrl()
            } else {
                "服务未运行。先 action=start（或 action=task 直接跑一次性任务，不需要起服务）。"
            }

            "logs" -> {
                val tail = args.optInt("tail", 80).coerceIn(10, 800)
                val all = h.logs()
                if (all.isBlank()) "（暂无日志）"
                else all.lines().takeLast(tail).joinToString("\n")
            }

            "diag" -> runBlocking { h.diagnose() }

            "set_key" -> {
                val provider = args.optString("provider", "deepseek").trim().lowercase()
                val key = args.optString("key", "").trim()
                if (key.isBlank()) return "缺少 key"
                h.setApiKey(provider, key)
                "已保存 $provider 的 API key（下次 start / task 时生效）"
            }

            "task" -> {
                val task = args.optString("task", "").trim()
                if (task.isBlank()) return "缺少 task（要交给 harness 执行的任务文本）"
                val sid = args.optString("session_id", "").trim().ifBlank { null }
                val json = args.optBoolean("json", false)
                val r = runBlocking { h.runTask(task, sid, json, timeoutMs) }
                buildString {
                    appendLine("DeepSeek Harness · headless 执行完成（退出码 ${r.code}" +
                        (if (r.timedOut) " · 超时" else "") + "）")
                    appendLine()
                    append(r.output.trim().ifBlank { "（无输出）" })
                }
            }

            "bridge_on" -> HarnessBridge.enable(context)

            "bridge_off" -> HarnessBridge.disable(context)

            "bridge_status" -> HarnessBridge.status(context)

            "plugins" -> HarnessBridge.capabilities(context)

            else -> "未知 action：$action"
        }
    }
}
