package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONObject

/**
 * 统一终端工具：把原来的 9 个独立终端工具合并为**一个** `terminal` 工具，用 action 参数分发。
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
【ZorvAI 终端】—— 应用内置终端会话的**底层驱动**（Termux 内部实现）。⚠️ 一般不要直接调用本工具。
🔴 与 `termux` 的区别：`termux` = Termux 官方 bootstrap（PREFIX/pkg），用户说「终端」默认用它；
   本工具（ZorvAI 终端）= 应用自己的终端会话驱动，管界面上那几个终端窗口。

🔴 用户说「终端 / Termux / 命令行 / 跑个命令 / 装包 / bash / shell」时，
   请调用 **`termux`** 工具 —— 那是 Termux 的对外统一入口，多会话（session_new/sessions/
   session_switch/session_kill/write/interrupt）与环境执行（run/pkg）都在那里，语义完整且一致。

什么时候才直接用本工具：
- 需要操作「界面上已经打开的那个交互式终端」的底层会话（termux 转发到的就是这里）；
- `termux` 明确返回环境不可用时的降级路径。

各 action（仅供排查/降级时参考）：
- run：应用沙盒内一次性执行设备 sh 命令（无 proot），返回 {exit_code, success, timed_out, output}
- exec：在 proot + Ubuntu 里执行命令，返回带 distro 字段
- write / interrupt / kill：向会话写一行输入 / 中断当前命令 / 结束会话
- status / sessions / session_new / session_switch / session_kill：会话状态与多会话管理

示例（降级时）：
- terminal(action="exec", command="ls -la")
- terminal(action="sessions")
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
