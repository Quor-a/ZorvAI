package com.ai.assistance.quro.kaleidobox.samples

import com.ai.assistance.quro.kaleidobox.core.engine.InvokeContext
import com.ai.assistance.quro.kaleidobox.core.engine.KaleidoToolkit
import com.ai.assistance.quro.kaleidobox.core.engine.ToolkitHost
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.ui.*
import com.ai.assistance.quro.kaleidobox.core.util.Json

/**
 * 真能力插件：终端（完整 app）。
 *
 * 直接驱动宿主的 Linux / proot 环境（[app.term.run]），并补齐一个完整终端该有的能力：
 *   - 常用命令快捷按钮（ls / pwd / whoami / uname / df / ps …）一键执行；
 *   - 命令历史【持久化】（[data.kv]，关掉再开还在）；
 *   - 输出一键复制到剪贴板、清空记录；
 *   - 每条命令回显退出码与输出。
 */
class TerminalToolkit : KaleidoToolkit {

    private var host: ToolkitHost? = null

    override fun attach(host: ToolkitHost) { this.host = host }

    /** 快捷命令：标签 → 实际命令。 */
    private val quick = listOf(
        "ls" to "ls -la",
        "pwd" to "pwd",
        "whoami" to "whoami",
        "uname" to "uname -a",
        "df" to "df -h",
        "ps" to "ps -ef",
        "date" to "date",
        "ip" to "ip addr",
    )

    private val kvKey = "term_history"
    private val maxBlocks = 60

    override fun invoke(fn: String, args: KValue, ctx: InvokeContext): KValue = when (fn) {
        "about_terminal" -> KValue.Str("终端：在宿主 Linux / proot 环境执行命令，含快捷命令、持久化历史、复制输出。")
        "render" -> KValue.Str(Json.write(UiCodec.encode(buildUi(args))))
        "onAction" -> handleAction(args)
        else -> KValue.fail("E_NO_FN", "未知函数: $fn")
    }

    // ---------------------------------------------------------------- 持久化

    private fun loadHistory(): List<String> {
        val r = host?.call("data.kv", KValue.obj("op" to "get", "key" to kvKey)) ?: KValue.Null
        val str = (r as? KValue.Str)?.value ?: return emptyList()
        val parsed = runCatching { Json.parse(str) }.getOrNull()
        return (parsed as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
    }

    private fun saveHistory(history: List<String>) {
        val tail = if (history.size > maxBlocks) history.takeLast(maxBlocks) else history
        host?.call(
            "data.kv",
            KValue.obj("op" to "set", "key" to kvKey, "value" to Json.write(KValue.of(tail))),
        )
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(args: KValue): UiNode {
        val state = SamplesUi.readState(args)
        var history = SamplesUi.strList(state, "history")
        if (history.isEmpty()) history = loadHistory()
        val command = (state["command"] as? String) ?: ""

        return SamplesUi.scrollPage(
            "root",
            content = listOf(
                SamplesUi.section("sec", "终端 · 宿主 Linux / proot 环境"),
                quickRow(),
                SamplesUi.codeBlock(
                    "term", history,
                    "（还没有执行命令，下面输入试试 ls -la，或点上方快捷命令）",
                    label = "输出", fill = true,
                ),
            ),
            actions = listOf(
                UiNode.Row(
                    "cmdRow",
                    modifier = Mod(padding = Edges(0, 0, 0, 8)),
                    children = listOf(
                        UiNode.TextField(
                            "command", Bound.Ref("command"), Action.of("command"),
                            label = "输入命令，如 ls -la", singleLine = true,
                            modifier = Mod(weight = 1f, minHeight = 52),
                        ),
                        UiNode.Button(
                            "run", Bound.Lit("运行"), Action.of("run"),
                            variant = UiNode.Button.Variant.FILLED,
                            modifier = Mod(padding = Edges(0, 0, 0, 8), minHeight = 52),
                        ),
                    ),
                ),
                SamplesUi.secondaryAction("copy", "复制全部输出"),
                SamplesUi.secondaryAction("clear", "清空记录"),
            ),
        )
    }

    /** 常用命令：横向滚动的快捷按钮，点一下立即执行。 */
    private fun quickRow(): UiNode = UiNode.Scroll(
        "quick", vertical = false,
        modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 6)),
        child = UiNode.Row(
            "quick_r",
            children = quick.map { (label, cmd) ->
                UiNode.Button(
                    "q_$cmd", Bound.Lit(label), Action.of("quick", "value" to cmd),
                    variant = UiNode.Button.Variant.TONAL,
                    modifier = Mod(padding = Edges(0, 0, 0, 8)),
                )
            },
        ),
    )

    // ---------------------------------------------------------------- 行为

    private fun handleAction(args: KValue): KValue {
        val obj = (args as? KValue.Obj)?.value ?: emptyMap()
        val state = SamplesUi.readState(args)
        val actionId = obj["actionId"]?.asString() ?: ""
        val payload = (obj["payload"] as? KValue.Obj)?.value ?: emptyMap()
        var command = (state["command"] as? String) ?: ""
        var history = SamplesUi.strList(state, "history").toMutableList()
        if (history.isEmpty()) history = loadHistory().toMutableList()

        fun exec(cmd: String) {
            history.add(runCmd(cmd))
        }

        when (actionId) {
            "command" -> command = payload["value"]?.asString() ?: ""
            "quick" -> {
                val cmd = payload["value"]?.asString()?.trim().orEmpty()
                if (cmd.isNotEmpty()) { exec(cmd); saveHistory(history) }
            }
            "run" -> {
                val cmd = command.trim()
                if (cmd.isNotEmpty()) {
                    exec(cmd)
                    command = ""
                    saveHistory(history)
                }
            }
            "copy" -> {
                if (history.isNotEmpty()) {
                    host?.call("ui.clipboard", KValue.obj("text" to history.joinToString("\n\n")))
                    host?.call("ui.toast", KValue.obj("text" to "输出已复制"))
                }
            }
            "clear" -> {
                history.clear()
                command = ""
                saveHistory(history)
            }
        }
        return KValue.obj("command" to command, "history" to history)
    }

    private fun runCmd(cmd: String): String {
        val r = host?.call("app.term.run", KValue.obj("command" to cmd, "timeout" to 30000)) ?: KValue.Null
        return when (r) {
            is KValue.Obj -> {
                val code = r.value["code"]?.asLongOr() ?: -1
                val out = r.value["output"]?.asString() ?: ""
                "$ $cmd\n[exit $code]\n$out"
            }
            is KValue.Err -> "$ $cmd\n[${r.code}] ${r.message}"
            else -> "$ $cmd\n（无响应）"
        }
    }
}
