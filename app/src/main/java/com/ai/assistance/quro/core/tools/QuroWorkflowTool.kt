package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多步工作流编排：把多个已注册工具串成流水线，支持条件跳过、变量替换与循环。
 * 通过 QuroToolRegistry.active 链式调用其它已注册工具。
 */
class QuroWorkflowTool : QuroTool {
    override val name = "workflow_run"
    override val description = "多步工作流编排：把多个已注册工具串成流水线，支持条件跳过、变量替换与循环。" +
        "参数 {\"steps\":[ {\"tool\":\"工具名\",\"args\":{...},\"if_contains\":\"上一输出需包含的文本（可选，不满足则跳过该步）\",\"if_not_contains\":\"...（可选）\",\"repeat\":N（可选循环 N 次）} ]}。" +
        "args 与 if_* 支持占位符 {{last}}（上一步输出）、{{i}}（循环序号）。返回每步结果汇总。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "steps":{"type":"array","description":"步骤数组，每步含 tool / args / 可选条件与循环"}
        },
        "required":["steps"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val steps = jo.optJSONArray("steps") ?: return "❌ 缺少 steps 数组"
        val reg = QuroToolRegistry.active ?: return "❌ 工具注册表不可用"
        val sb = StringBuilder()
        var last = ""
        var overallOk = true
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            val toolName = step.optString("tool", "")
            val rep = step.optInt("repeat", 1).coerceIn(1, 10)
            val ifc = step.optString("if_contains", "")
            val ifnc = step.optString("if_not_contains", "")
            if (ifc.isNotBlank() && !last.contains(ifc)) {
                sb.append("⏭️ 步骤${i + 1} [$toolName] 跳过（上一输出不含「$ifc」）\n")
                continue
            }
            if (ifnc.isNotBlank() && last.contains(ifnc)) {
                sb.append("⏭️ 步骤${i + 1} [$toolName] 跳过（上一输出含「$ifnc」）\n")
                continue
            }
            val tool = reg.get(toolName)
            if (tool == null) {
                sb.append("❌ 步骤${i + 1} 未知工具：$toolName\n")
                overallOk = false
                continue
            }
            val argsTemplate = step.optJSONObject("args")?.toString() ?: "{}"
            var stepOut = ""
            for (r in 0 until rep) {
                val args = argsTemplate
                    .replace("{{last}}", last)
                    .replace("{{i}}", r.toString())
                stepOut = runCatching { tool.run(context, args) }.getOrDefault("❌ 执行异常")
                last = stepOut
            }
            sb.append("✅ 步骤${i + 1} [$toolName]${if (rep > 1) " ×$rep" else ""}：\n${stepOut.take(4000)}\n\n")
        }
        sb.insert(0, if (overallOk) "🔧 工作流完成（共 ${steps.length()} 步）\n\n" else "⚠️ 工作流存在失败步骤\n\n")
        return sb.toString().trim()
    }
}
