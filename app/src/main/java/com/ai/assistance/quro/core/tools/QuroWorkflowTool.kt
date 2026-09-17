package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 多步工作流编排引擎：把多个已注册工具串成流水线，支持变量、循环、条件、错误处理与超时。
 * 通过 QuroToolRegistry.active 链式调用其它已注册工具。旧字段（tool/args/if_contains/if_not_contains/repeat）全兼容。
 */
class QuroWorkflowTool : QuroTool {
    override val name = "workflow_run"
    override val description = "多步工作流编排引擎：把已注册工具串成流水线，支持变量、循环、条件、错误处理与超时，用它编排其它工具补齐能力缺口。" +
        "参数 {\"vars\":{初始变量(可选)},\"steps\":[ 步骤... ]}。" +
        "步骤类型：\n" +
        " - 工具步：{\"tool\":\"工具名\",\"args\":{...}(支持占位符 {{var}}/{{last}}/{{i}}/{{item}})," +
        "\"save_as\":\"变量名(可选,存本步输出供后续引用)\"," +
        "\"if_contains\":\"上一输出需包含(可选)\",\"if_not_contains\":\"...(可选)\"," +
        "\"if_eq\":\"变量==值(可选,不满足跳过)\",\"if_ne\":\"变量!=值(可选)\"," +
        "\"repeat\":N(可选固定循环N次,1-50),\"for_each\":[...]或\"{{var}}\"(可选遍历数组,绑定 {{item}})," +
        "\"timeout_ms\":可选单步超时(0=不限,最大300000),\"on_error\":\"continue|abort(默认continue)}\n" +
        " - 赋值步：{\"set\":\"变量名\",\"value\":\"值(支持 {{占位符}})\"}\n" +
        "占位符在 args / if_* / value 中解析；返回每步结果汇总与最终变量表。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "vars":{"type":"object","description":"初始变量表（可选），步骤内用 {{key}} 引用"},
            "steps":{"type":"array","description":"步骤数组。工具步: {tool,args,save_as?,if_contains?,if_not_contains?,if_eq?,if_ne?,repeat?,for_each?,timeout_ms?,on_error?}；赋值步: {set,value}"}
        },
        "required":["steps"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val steps = jo.optJSONArray("steps") ?: return "❌ 缺少 steps 数组"
        val reg = QuroToolRegistry.active ?: return "❌ 工具注册表不可用"
        val vars = mutableMapOf<String, String>()
        jo.optJSONObject("vars")?.let { v -> v.keys().forEach { vars[it] = v.optString(it) } }
        val sb = StringBuilder()
        var last = ""
        var overallOk = true
        val placeholder = Regex("""\{\{(\w+)\}\}""")

        fun resolve(template: String, item: String = "", idx: Int = -1): String {
            return placeholder.replace(template) { m ->
                when (val k = m.groupValues[1]) {
                    "last" -> last
                    "i" -> if (idx >= 0) idx.toString() else ""
                    "item" -> item
                    else -> vars[k] ?: ""
                }
            }
        }

        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue

            // 赋值步
            if (step.has("set")) {
                val key = step.optString("set")
                val value = resolve(step.optString("value", ""))
                vars[key] = value
                sb.append("🔧 步骤${i + 1} [set $key = ${value.take(200)}]\n")
                continue
            }

            val toolName = step.optString("tool", "")

            // 条件跳过
            val ifc = step.optString("if_contains", "")
            val ifnc = step.optString("if_not_contains", "")
            if (ifc.isNotBlank() && !last.contains(ifc)) {
                sb.append("⏭️ 步骤${i + 1} [$toolName] 跳过（last 不含「$ifc」）\n"); continue
            }
            if (ifnc.isNotBlank() && last.contains(ifnc)) {
                sb.append("⏭️ 步骤${i + 1} [$toolName] 跳过（last 含「$ifnc」）\n"); continue
            }
            val ifeq = step.optString("if_eq", "")
            if (ifeq.isNotBlank()) {
                val (vk, vv) = ifeq.split("==", limit = 2).let { (it.getOrNull(0) ?: "").trim() to (it.getOrNull(1) ?: "").trim() }
                if (vars[vk]?.trim() != vv) { sb.append("⏭️ 步骤${i + 1} [$toolName] 跳过（{{$vk}}!=${vv}）\n"); continue }
            }
            val ifne = step.optString("if_ne", "")
            if (ifne.isNotBlank()) {
                val (vk, vv) = ifne.split("!=", limit = 2).let { (it.getOrNull(0) ?: "").trim() to (it.getOrNull(1) ?: "").trim() }
                if (vars[vk]?.trim() == vv) { sb.append("⏭️ 步骤${i + 1} [$toolName] 跳过（{{$vk}}==${vv}）\n"); continue }
            }

            val tool = reg.get(toolName)
            if (tool == null) { sb.append("❌ 步骤${i + 1} 未知工具：$toolName\n"); overallOk = false; continue }

            val argsTemplate = step.optJSONObject("args")?.toString() ?: "{}"
            val saveAs = step.optString("save_as", "")
            val timeout = step.optInt("timeout_ms", 0).coerceIn(0, 300000)
            val onError = step.optString("on_error", "continue")
            val rep = step.optInt("repeat", 1).coerceIn(1, 50)

            // 遍历集：for_each 优先，否则按 repeat 固定次数
            val forEachRaw = step.opt("for_each")
            val items: List<String> = when {
                forEachRaw is JSONArray ->
                    (0 until forEachRaw.length()).map { forEachRaw.optString(it) }
                forEachRaw is String && forEachRaw.startsWith("{{") -> {
                    val vn = forEachRaw.removeSurrounding("{{", "}}").trim()
                    vars[vn]?.let { runCatching { JSONArray(it) }.getOrNull() }
                        ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
                }
                forEachRaw is String ->
                    runCatching { JSONArray(forEachRaw) }.getOrNull()
                        ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: listOf(forEachRaw)
                else -> emptyList()
            }

            var stepOut = ""
            val iterCount = if (items.isNotEmpty()) items.size else rep
            var aborted = false
            for (r in 0 until iterCount) {
                val item = if (items.isNotEmpty()) items[r] else ""
                val args = resolve(argsTemplate, item = item, idx = r)
                stepOut = callTool(context, tool, args, timeout)
                last = stepOut
                if (stepOut.startsWith("❌")) {
                    sb.append("❌ 步骤${i + 1} [$toolName]${if (items.isNotEmpty()) " item$r" else if (rep > 1) " #$r" else ""} 失败：${stepOut.take(500)}\n")
                    overallOk = false
                    if (onError == "abort") { aborted = true; break }
                }
            }
            if (aborted) { sb.append("⛔ 工作流在步骤${i + 1} 中止（on_error=abort）\n"); overallOk = false; break }
            if (saveAs.isNotBlank()) vars[saveAs] = stepOut
            sb.append("✅ 步骤${i + 1} [$toolName]${if (items.isNotEmpty()) " ×${items.size}" else if (rep > 1) " ×$rep" else ""}${if (saveAs.isNotBlank()) " → $saveAs" else ""}：\n${stepOut.take(4000)}\n\n")
        }

        sb.insert(0, if (overallOk) "🔧 工作流完成（共 ${steps.length()} 步）\n\n" else "⚠️ 工作流存在失败步骤\n\n")
        if (vars.isNotEmpty()) {
            sb.append("\n📦 最终变量：\n")
            vars.forEach { (k, v) -> sb.append("  $k = ${v.take(300)}\n") }
        }
        return sb.toString().trim()
    }

    /** 带超时地同步调用工具（超时用独立线程 + join 实现，best-effort）。 */
    private fun callTool(ctx: Context, tool: QuroTool, args: String, timeoutMs: Int): String {
        if (timeoutMs <= 0) return runCatching { tool.run(ctx, args) }.getOrDefault("❌ 执行异常")
        val result = AtomicReference("")
        val done = AtomicBoolean(false)
        val t = Thread {
            runCatching { result.set(tool.run(ctx, args)) }.onFailure { result.set("❌ 执行异常") }
            done.set(true)
        }
        t.start()
        t.join(timeoutMs.toLong())
        return if (done.get()) result.get() else "❌ 步骤超时（>${timeoutMs}ms）"
    }
}
