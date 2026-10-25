package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.dshmobile.DshmobileControl
import org.json.JSONObject

/**
 * DeepSeek Harness（dsh）工具 —— ZorvAI 完整操控内置 harness 的控制面（#404）。
 *
 * 两条使用路径，对应两种真实需求：
 *
 *  1. **给人用**：`open` 打开 harness 工作台（会话、插件、设置、模型选择都是它自己的 UI）；
 *  2. **给 AI 用**：`task` 把任务派给 harness 的 agent 完整执行，答案经 RPC 回收。
 *     适合「这件活儿需要一整套 agent 工作流」的场景。
 *
 * ## 同一环境（2026-10 用户拍板语义）
 *
 * harness 与 ZorvAI 不是两个产品：harness 运行时内置在 ZorvAI 里（同一应用、同一文件域），
 * ZorvAI 的 AI 经本工具起停它、派任务给它；harness 的 agent 经 MCP 桥反向调用 ZorvAI 的全部
 * 工具（`mcp__zorvai__*`）。互相称呼用「我们」，任务描述要自包含。
 *
 * ## 实现说明
 *
 * 全部动作收敛到 [DshmobileControl] 门面（dshmobile 模块内的 public 单例）：
 * 引擎 spawn 权威仍是 EngineManager 的四道闸门，保活仍是 EngineService 看门狗，
 * 本工具不自己碰进程/端口/文件路径。
 */
class HarnessTool : QuroTool {
    override val name: String = "harness"

    override val description: String =
        "DeepSeek Harness（dsh）—— 内置在 ZorvAI 里的完整 agent harness（我们环境的一部分）：" +
            "自带 Node 运行时、agent 引擎、Web 界面（会话/插件/设置全在它自己 UI 里），" +
            "不需要 Ubuntu、不需要联网安装、不经终端。用法：" +
            "open 打开它的工作台界面（给人用）；" +
            "start 起引擎（首次会自动解压内置快照，分钟级；之后 20-45s 冷启动）；" +
            "**task 是最重要的动作** —— 把任务交给它的 agent 完整执行（读文件、跑命令、改代码、" +
            "用它自己的插件），然后把最终答复返回，可带 session_id 续用同一会话多轮协作；" +
            "stop/restart 停/重启；status/url/logs/diag 看状态与排障；" +
            "set_key 存它自己的模型 key（harness 是独立 agent，模型配置与 ZorvAI 互不相干）；" +
            "bridge_on 把 ZorvAI 的全部工具经 MCP 暴露给它（它以 mcp__zorvai__<工具名> 调用，" +
            "真正执行的是 ZorvAI 的工具）；bridge_off 撤销；bridge_status 看桥接状态；" +
            "plugins 列出它已装的全部包（它的能力来源），知道它有什么才能在 task 里准确提要求。"

    override val parametersJson: String = """{
   "type":"object",
   "properties":{
      "action":{"type":"string","description":"操作","enum":["open","start","stop","restart","status","logs","url","task","set_key","diag","bridge_on","bridge_off","bridge_status","plugins"]},
      "task":{"type":"string","description":"task 动作要交给 harness 执行的任务文本（一句话描述它要做什么，它会自己规划并执行；描述要自包含，它看不到我们的对话上下文）"},
      "session_id":{"type":"string","description":"task 时可选：续用某个会话 id（上一轮 task 的回执里会带），让多轮任务有上下文"},
      "key":{"type":"string","description":"set_key 时要保存的 API key（harness 自己的模型 key）"},
      "timeout_seconds":{"type":"number","description":"task 的超时秒数，默认 600；agent 干活可能很慢，别设太小"},
      "tail":{"type":"number","description":"logs 返回的尾部行数，默认 80"}
   },
   "required":["action"]
}"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val action = args.optString("action", "").trim().lowercase()
        if (action.isBlank()) {
            return "缺少 action。可用：open/start/stop/restart/status/logs/url/task/set_key/diag/" +
                "bridge_on/bridge_off/bridge_status/plugins"
        }
        val timeoutMs = (args.optDouble("timeout_seconds", 600.0).toLong()).coerceIn(30, 3600) * 1000L

        return when (action) {
            "open" -> DshmobileControl.openWorkbench(context).text

            "start" -> DshmobileControl.start(context).text

            "stop" -> DshmobileControl.stop(context).text

            "restart" -> DshmobileControl.restart(context).text

            "status" -> DshmobileControl.status(context).text

            "url" -> if (DshmobileControl.status(context).ok) {
                DshmobileControl.engineUrl() + "\n（给人看请用 action=open 打开工作台，界面与鉴权由它自己处理）"
            } else {
                "引擎未运行。先 action=start（或 action=task 直接派任务，不需要手动起服务）。"
            }

            "logs" -> {
                val tail = args.optInt("tail", 80).coerceIn(10, 800)
                DshmobileControl.engineLogs(context, tail)
            }

            "diag" -> buildString {
                appendLine(DshmobileControl.status(context).text)
                appendLine()
                appendLine("=== engine.log 尾部（token 已脱敏）===")
                appendLine(DshmobileControl.engineLogs(context, 60))
            }

            "set_key" -> {
                val key = args.optString("key", "").trim()
                if (key.isBlank()) return "缺少 key（harness 自己的模型 API key）"
                DshmobileControl.setApiKey(context, key).text
            }

            "task" -> {
                val task = args.optString("task", "").trim()
                if (task.isBlank()) return "缺少 task（要交给 harness 执行的任务文本）"
                val sid = args.optString("session_id", "").trim().ifBlank { null }
                DshmobileControl.dispatchTask(context, task, sid, timeoutMs).text
            }

            "bridge_on" -> HarnessBridge.enable(context)

            "bridge_off" -> HarnessBridge.disable(context)

            "bridge_status" -> HarnessBridge.status(context)

            "plugins" -> HarnessBridge.capabilities(context)

            else -> "未知 action：$action"
        }
    }
}
