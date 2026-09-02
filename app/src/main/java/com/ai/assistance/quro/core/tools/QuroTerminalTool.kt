package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONObject

/**
 * 统一终端工具：把原来的 10 个独立终端工具合并为**一个** `terminal` 工具，用 action 参数分发。
 *
 * 被合并的工具（逻辑完全复用，仅注册集中到本工具）：
 * - terminal_run      → action=run          （设备沙盒一次性 exec，无 proot）
 * - terminal_exec     → action=exec         （proot/Linux exec，回退设备 sh）
 * - terminal_write    → action=write        （向当前交互式会话写一行输入）
 * - terminal_kill     → action=kill         （结束当前会话）
 * - terminal_status   → action=status       （会话状态 + 会话列表）
 * - terminal_interrupt→ action=interrupt    （中断当前运行中的命令）
 * - terminal_sessions → action=sessions     （列出所有会话）
 * - terminal_session_new    → action=session_new    （新建会话）
 * - terminal_session_switch→ action=session_switch （切换默认会话）
 * - terminal_session_kill  → action=session_kill  （销毁指定会话）
 *
 * 设计：本类持有 10 个子工具实例，run() 按 action 构造对应的内部参数 JSON 后委托执行，
 * 因此每个 action 的返回结构/语义与原工具完全一致。AI 只需记住一个工具名 `terminal`。
 */
class QuroTerminalTool : QuroTool {
    override val name: String = "terminal"

    override val description: String = """
应用内终端统一工具（原 10 个 terminal_* 工具合并为一个）。通过 action 分发，所有终端能力都在这里：

- run: 应用沙盒内一次性执行 shell 命令（设备 sh，无 proot/Linux）。返回 JSON：{exit_code, success, timed_out, output}。务必检查 exit_code/success。
- exec: 在 proot/Linux 环境执行命令（自动走 proot，不可用时回退设备 sh）。返回 JSON：{source, exit_code, success, timed_out, output}。务必检查 exit_code/success。
- write: 向当前交互式终端会话写入一行输入并回车（等价于在提示符后敲回车）。需终端界面已打开且存在活动会话；常用于让 AI 替用户在终端里执行命令或喂给交互式程序（如 python REPL）。
- kill: 结束当前交互式终端会话（销毁常驻 shell 进程）。
- status: 返回终端会话状态（是否存在、模式、是否忙碌、cwd、last_exit、last_interrupted）与全部会话列表。
- interrupt: 中断交互式终端里正在运行的命令（等价于按 Ctrl+C / 界面「■ 中断」按钮）。
- sessions: 列出所有终端会话（默认/额外/历史），含 id、名称、后端、是否默认、是否存活。
- session_new: 创建一个新的终端会话（后端与默认一致），不自动成为默认。可选 name。
- session_switch: 把指定 id 的会话切换为默认共享会话（AI 工具 / CMS 此后将使用该会话）。id 来自 sessions 的返回。
- session_kill: 销毁指定 id 的会话；id=default 或省略则销毁默认共享会话。

参数：action(必填) + 按 action 选填 command / text / id / name。
示例：
- terminal(action="exec", command="ls -la")
- terminal(action="write", text="pwd")
- terminal(action="status")
- terminal(action="session_switch", id="ab12cd34")
""".trimIndent()

    override val parametersJson: String = """
    {"type":"object","properties":{
      "action":{"type":"string","description":"run/exec/write/kill/status/interrupt/sessions/session_new/session_switch/session_kill"},
      "command":{"type":"string","description":"run / exec 用的 shell 命令"},
      "text":{"type":"string","description":"write 用的输入内容（一行命令）"},
      "id":{"type":"string","description":"session_switch / session_kill 用的会话 id（session_kill 缺省 default）"},
      "name":{"type":"string","description":"session_new 用的会话名（可选）"}
    },"required":["action"]}
    """.trimIndent()

    // 复用各子工具实例，逻辑不变
    private val runTool = TerminalDriveTool()
    private val execTool = TerminalExecTool()
    private val writeTool = TerminalWriteTool()
    private val killTool = TerminalKillTool()
    private val statusTool = TerminalStatusTool()
    private val interruptTool = TerminalInterruptTool()
    private val sessionsTool = TerminalSessionsTool()
    private val sessionNewTool = TerminalSessionNewTool()
    private val sessionSwitchTool = TerminalSessionSwitchTool()
    private val sessionKillTool = TerminalSessionKillTool()

    override fun run(context: Context, arguments: String): String {
        val obj = JSONObject(arguments)
        val action = obj.optString("action", "")
        val inner = JSONObject()
        return when (action) {
            "run" -> {
                inner.put("command", obj.optString("command", ""))
                runTool.run(context, inner.toString())
            }
            "exec" -> {
                inner.put("command", obj.optString("command", ""))
                execTool.run(context, inner.toString())
            }
            "write" -> {
                inner.put("text", obj.optString("text", ""))
                writeTool.run(context, inner.toString())
            }
            "kill" -> killTool.run(context, "{}")
            "status" -> statusTool.run(context, "{}")
            "interrupt" -> interruptTool.run(context, "{}")
            "sessions" -> sessionsTool.run(context, "{}")
            "session_new" -> {
                inner.put("name", obj.optString("name", ""))
                sessionNewTool.run(context, inner.toString())
            }
            "session_switch" -> {
                inner.put("id", obj.optString("id", ""))
                sessionSwitchTool.run(context, inner.toString())
            }
            "session_kill" -> {
                val id = obj.optString("id", "default").ifBlank { "default" }
                inner.put("id", id)
                sessionKillTool.run(context, inner.toString())
            }
            else -> "不支持的 action: $action\n可用: run/exec/write/kill/status/interrupt/sessions/session_new/session_switch/session_kill"
        }
    }
}
