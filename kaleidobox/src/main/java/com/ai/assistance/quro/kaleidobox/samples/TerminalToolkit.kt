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
 * 直接驱动宿主的 Linux / proot 环境（`app.term.run`），并按"一个真终端该有什么"来补：
 *   - 快捷命令（内置 + **用户自定义**，可增删并持久化）；
 *   - 命令历史：**持久化** + 「上一条 / 下一条」召回；
 *   - **多行输入**（写多行脚本，如 for 循环）；
 *   - **输出检索**（过滤显示）与输出统计；
 *   - 复制全部输出、清屏；
 *   - 每条命令回显 `$ cmd` 与退出码 `[exit N]`。
 *
 * 布局硬约束：根节点子项数量固定，输出区用 weight 占满剩余高度（不是固定高度），
 * 这样键盘弹出/收起时输出区会自动收缩，输入栏始终可见。
 */
class TerminalToolkit : KaleidoToolkit {

    private var host: ToolkitHost? = null

    override fun attach(host: ToolkitHost) { this.host = host }

    /** 内置快捷命令：标签 → 实际命令。 */
    private val builtinQuick = listOf(
        "ls" to "ls -la",
        "pwd" to "pwd",
        "whoami" to "whoami",
        "uname" to "uname -a",
        "df" to "df -h",
        "ps" to "ps -ef",
        "date" to "date",
        "ip" to "ip addr",
        "环境" to "cat /etc/os-release",
        "进程" to "top -bn1 | head -20",
    )

    private val logKey = "term_output"
    private val cmdKey = "term_cmds"
    private val quickKey = "term_quick"

    private val maxBlocks = 80
    private val maxCmds = 60

    override fun invoke(fn: String, args: KValue, ctx: InvokeContext): KValue = when (fn) {
        "about_terminal" -> KValue.Str(
            "终端：在宿主 Linux / proot 环境执行命令；含快捷命令（可自定义）、" +
                "持久化历史与上一条/下一条召回、多行输入、输出检索、复制与清屏。"
        )
        "render" -> KValue.Str(Json.write(UiCodec.encode(buildUi(args))))
        "onAction" -> handleAction(args)
        else -> KValue.fail("E_NO_FN", "未知函数: $fn")
    }

    // ---------------------------------------------------------------- 持久化

    private fun kvGet(key: String): String {
        val r = host?.call("data.kv", KValue.obj("op" to "get", "key" to key)) ?: KValue.Null
        return (r as? KValue.Str)?.value ?: ""
    }

    private fun kvSet(key: String, value: String) {
        host?.call("data.kv", KValue.obj("op" to "set", "key" to key, "value" to value))
    }

    private fun loadList(key: String): MutableList<String> {
        val parsed = runCatching { Json.parse(kvGet(key)) }.getOrNull()
        return ((parsed as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()).toMutableList()
    }

    private fun saveList(key: String, list: List<String>) = kvSet(key, Json.write(KValue.of(list)))

    // ---------------------------------------------------------------- UI

    private fun buildUi(args: KValue): UiNode {
        val st = SamplesUi.readState(args)

        var out = SamplesUi.strList(st, "out")
        if (out.isEmpty()) out = loadList(logKey)
        var cmds = SamplesUi.strList(st, "cmds")
        if (cmds.isEmpty()) cmds = loadList(cmdKey)
        var custom = SamplesUi.strList(st, "custom")
        if (custom.isEmpty()) custom = loadList(quickKey)

        val command = SamplesUi.strOf(st, "command")
        val filter = SamplesUi.strOf(st, "filter")
        val multiline = SamplesUi.boolOf(st, "multiline")
        val editQuick = SamplesUi.boolOf(st, "editQuick")
        val newQuick = SamplesUi.strOf(st, "newQuick")
        val status = SamplesUi.strOf(st, "status")

        val shown = if (filter.isBlank()) out else out.filter { it.contains(filter, true) }

        return UiNode.Column(
            "root",
            modifier = Mod(width = Size.Fill, height = Size.Fill),
            children = listOf(
                quickBar(custom),
                quickEditor(editQuick, custom, newQuick),
                filterRow(out.size, shown.size, filter),
                SamplesUi.codeBlock(
                    "term", shown,
                    if (out.isEmpty()) "（还没有执行命令。输入 ls -la，或点上方快捷命令）"
                    else "（过滤后没有匹配的输出）",
                    fill = true, weight = 1f,
                ),
                inputRow(multiline),
                auxRow(multiline, filter),
                SamplesUi.hint("st", status.ifBlank { "输出区共 ${out.size} 块 · 历史命令 ${cmds.size} 条" }),
            ),
        )
    }

    /** 快捷命令条：内置 + 自定义 + 管理入口。 */
    private fun quickBar(custom: List<String>): UiNode =
        UiNode.Scroll(
            "quick", vertical = false,
            modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 6)),
            child = UiNode.Row(
                "quick_r",
                children = buildList {
                    builtinQuick.forEach { (label, cmd) ->
                        add(qbtn("q_$label", label, cmd))
                    }
                    custom.forEachIndexed { i, cmd ->
                        add(qbtn("qc_$i", "★ " + cmd.take(12), cmd))
                    }
                    add(
                        UiNode.Button(
                            "editQuick", Bound.Lit("＋ 自定义"), Action.of("editQuick"),
                            variant = UiNode.Button.Variant.OUTLINED,
                            modifier = Mod(padding = Edges(0, 0, 6, 8)),
                        )
                    )
                },
            ),
        )

    private fun qbtn(id: String, label: String, cmd: String): UiNode =
        UiNode.Button(
            id, Bound.Lit(label), Action.of("quick", "cmd" to cmd),
            variant = UiNode.Button.Variant.TONAL,
            modifier = Mod(padding = Edges(0, 0, 6, 8)),
        )

    /** 自定义命令编辑器：收起时高度 0（节点始终存在，保持根层结构稳定）。 */
    private fun quickEditor(open: Boolean, custom: List<String>, newQuick: String): UiNode =
        UiNode.Box(
            "quickEditBox",
            modifier = Mod(
                width = Size.Fill,
                height = Size.Dp(if (open) 168 else 0),
                background = if (open) SamplesUi.C.surface else null,
                cornerRadius = 14,
                border = if (open) Border(1, SamplesUi.C.line) else null,
                padding = if (open) Edges.all(12) else Edges(),
                margin = Edges(0, 0, 0, 8),
            ),
            children = if (!open) emptyList() else listOf(
                UiNode.Scroll(
                    "quickEditScroll", vertical = true,
                    modifier = Mod(width = Size.Fill, height = Size.Fill),
                    child = UiNode.Column(
                        "quickEditCol",
                        children = buildList {
                            add(SamplesUi.section("qe_t", "自定义快捷命令（${custom.size}）"))
                            add(
                                UiNode.Row(
                                    "qe_add",
                                    modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 4)),
                                    children = listOf(
                                        UiNode.TextField(
                                            "newQuick", Bound.Ref("newQuick"), Action.of("newQuick"),
                                            label = "新命令，如 df -h /data", singleLine = true,
                                            modifier = Mod(weight = 1f, minHeight = 50),
                                        ),
                                        UiNode.Button(
                                            "addQuick", Bound.Lit("添加"), Action.of("addQuick"),
                                            variant = UiNode.Button.Variant.FILLED,
                                            modifier = Mod(padding = Edges(6, 0, 0, 10), minHeight = 50),
                                        ),
                                    ),
                                )
                            )
                            if (custom.isEmpty()) {
                                add(SamplesUi.hint("qe_e", "还没有自定义命令。"))
                            } else {
                                custom.forEachIndexed { i, cmd ->
                                    add(
                                        UiNode.Row(
                                            "qe_r$i",
                                            modifier = Mod(width = Size.Fill, padding = Edges(0, 2, 0, 2)),
                                            children = listOf(
                                                UiNode.Text(
                                                    "qe_c$i", Bound.Lit(cmd), TypeStyle.MONO,
                                                    color = SamplesUi.C.inkSoft,
                                                    modifier = Mod(weight = 1f, padding = Edges(0, 12, 0, 12)),
                                                ),
                                                UiNode.Button(
                                                    "qe_d$i", Bound.Lit("✕"),
                                                    Action.of("delQuick", "idx" to i.toString()),
                                                    variant = UiNode.Button.Variant.TEXT,
                                                    modifier = Mod(padding = Edges(0, 0, 0, 6)),
                                                ),
                                            ),
                                        )
                                    )
                                }
                            }
                        },
                    ),
                ),
            ),
        )

    /** 输出区表头 + 输出检索。 */
    private fun filterRow(total: Int, shown: Int, filter: String): UiNode =
        UiNode.Row(
            "filterRow",
            modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 6)),
            children = listOf(
                UiNode.TextField(
                    "filter", Bound.Ref("filter"), Action.of("filter"),
                    label = if (filter.isBlank()) "检索输出（过滤显示）" else "过滤：$filter",
                    singleLine = true,
                    modifier = Mod(weight = 1f, minHeight = 48),
                ),
                UiNode.Text(
                    "filterCount", Bound.Lit("$shown/$total"), TypeStyle.CAPTION,
                    color = SamplesUi.C.muted,
                    modifier = Mod(padding = Edges(8, 14, 0, 0)),
                ),
            ),
        )

    private fun inputRow(multiline: Boolean): UiNode =
        UiNode.Row(
            "cmdRow",
            modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 6)),
            children = listOf(
                UiNode.TextField(
                    "command", Bound.Ref("command"), Action.of("command"),
                    label = if (multiline) "多行命令（每行一条，按顺序执行）" else "输入命令，如 ls -la",
                    singleLine = !multiline,
                    modifier = Mod(weight = 1f, minHeight = if (multiline) 96 else 52),
                ),
                UiNode.Button(
                    "run", Bound.Lit("▶ 运行"), Action.of("run"),
                    variant = UiNode.Button.Variant.FILLED,
                    modifier = Mod(padding = Edges(6, 0, 0, 10), minHeight = 52),
                ),
            ),
        )

    private fun auxRow(multiline: Boolean, filter: String): UiNode =
        UiNode.Scroll(
            "aux", vertical = false,
            modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 6)),
            child = UiNode.Row(
                "aux_r",
                children = listOf(
                    UiNode.Button(
                        "prevCmd", Bound.Lit("↑ 上一条"), Action.of("prevCmd"),
                        variant = UiNode.Button.Variant.OUTLINED,
                        modifier = Mod(padding = Edges(0, 0, 6, 8)),
                    ),
                    UiNode.Button(
                        "nextCmd", Bound.Lit("↓ 下一条"), Action.of("nextCmd"),
                        variant = UiNode.Button.Variant.OUTLINED,
                        modifier = Mod(padding = Edges(0, 0, 6, 8)),
                    ),
                    UiNode.Button(
                        "mlToggle", Bound.Lit(if (multiline) "单行模式" else "多行模式"),
                        Action.of("mlToggle"),
                        variant = if (multiline) UiNode.Button.Variant.FILLED
                        else UiNode.Button.Variant.OUTLINED,
                        modifier = Mod(padding = Edges(0, 0, 6, 8)),
                    ),
                    UiNode.Button(
                        "copy", Bound.Lit("复制输出"), Action.of("copy"),
                        variant = UiNode.Button.Variant.OUTLINED,
                        modifier = Mod(padding = Edges(0, 0, 6, 8)),
                    ),
                    UiNode.Button(
                        "clear", Bound.Lit("清屏"), Action.of("clear"),
                        variant = UiNode.Button.Variant.OUTLINED,
                        modifier = Mod(padding = Edges(0, 0, 6, 8)),
                    ),
                ),
            ),
        )

    // ---------------------------------------------------------------- 行为

    private fun handleAction(args: KValue): KValue {
        val obj = (args as? KValue.Obj)?.value ?: emptyMap()
        val st = SamplesUi.readState(args)
        val actionId = obj["actionId"]?.asString() ?: ""
        val payload = (obj["payload"] as? KValue.Obj)?.value ?: emptyMap()

        fun p(k: String): String = payload[k]?.asString() ?: ""

        var out = SamplesUi.strList(st, "out").toMutableList()
        if (out.isEmpty()) out = loadList(logKey)
        var cmds = SamplesUi.strList(st, "cmds").toMutableList()
        if (cmds.isEmpty()) cmds = loadList(cmdKey)
        var custom = SamplesUi.strList(st, "custom").toMutableList()
        if (custom.isEmpty()) custom = loadList(quickKey)

        var command = SamplesUi.strOf(st, "command")
        var filter = SamplesUi.strOf(st, "filter")
        var multiline = SamplesUi.boolOf(st, "multiline")
        var editQuick = SamplesUi.boolOf(st, "editQuick")
        var newQuick = SamplesUi.strOf(st, "newQuick")
        var histIdx = SamplesUi.intOf(st, "histIdx", -1)
        var status = ""

        fun exec(raw: String) {
            val cmd = raw.trim()
            if (cmd.isEmpty()) return
            cmds.remove(cmd)
            cmds.add(cmd)
            if (cmds.size > maxCmds) cmds = cmds.takeLast(maxCmds).toMutableList()
            out.add(runCmd(cmd))
            if (out.size > maxBlocks) out = out.takeLast(maxBlocks).toMutableList()
            saveList(logKey, out)
            saveList(cmdKey, cmds)
            histIdx = -1
        }

        when (actionId) {
            "command" -> command = p("value")

            "quick" -> exec(p("cmd"))

            "run" -> {
                if (command.isNotBlank()) {
                    exec(command)
                    command = ""
                }
            }

            "prevCmd" -> {
                if (cmds.isNotEmpty()) {
                    histIdx = when {
                        histIdx < 0 -> cmds.size - 1
                        histIdx > 0 -> histIdx - 1
                        else -> 0
                    }
                    command = cmds[histIdx]
                } else {
                    status = "还没有执行过命令"
                }
            }

            "nextCmd" -> {
                if (histIdx >= 0 && cmds.isNotEmpty()) {
                    histIdx += 1
                    if (histIdx >= cmds.size) {
                        histIdx = -1
                        command = ""
                    } else {
                        command = cmds[histIdx]
                    }
                }
            }

            "mlToggle" -> multiline = !multiline

            "copy" -> {
                if (out.isNotEmpty()) {
                    host?.call("ui.clipboard", KValue.obj("text" to out.joinToString("\n\n")))
                    status = "输出已复制（${out.size} 块）"
                }
            }

            "clear" -> {
                out.clear()
                command = ""
                histIdx = -1
                saveList(logKey, out)
            }

            "filter" -> filter = p("value")

            "editQuick" -> editQuick = !editQuick

            "newQuick" -> newQuick = p("value")

            "addQuick" -> {
                val c = newQuick.trim()
                when {
                    c.isEmpty() -> status = "请输入要添加的命令"
                    c in custom -> status = "该命令已在列表中"
                    else -> {
                        custom.add(c)
                        saveList(quickKey, custom)
                        newQuick = ""
                        status = "已添加快捷命令"
                    }
                }
            }

            "delQuick" -> {
                val i = p("idx").toIntOrNull() ?: -1
                if (i in custom.indices) {
                    custom.removeAt(i)
                    saveList(quickKey, custom)
                }
            }
        }

        return KValue.obj(
            "out" to out, "cmds" to cmds, "custom" to custom,
            "command" to command, "filter" to filter,
            "multiline" to multiline, "editQuick" to editQuick, "newQuick" to newQuick,
            "histIdx" to histIdx, "status" to status,
        )
    }

    /** 执行一条命令，格式化成终端样式的输出块。 */
    private fun runCmd(cmd: String): String {
        val r = host?.call("app.term.run", KValue.obj("command" to cmd, "timeout" to 30000)) ?: KValue.Null
        return when (r) {
            is KValue.Obj -> {
                val code = r.value["code"]?.asLongOr() ?: -1
                val output = r.value["output"]?.asString() ?: ""
                "$ $cmd\n[exit $code]\n${output.trimEnd()}"
            }
            is KValue.Err -> "$ $cmd\n[${r.code}] ${r.message}"
            else -> "$ $cmd\n（无响应：宿主终端能力不可用？）"
        }
    }
}
