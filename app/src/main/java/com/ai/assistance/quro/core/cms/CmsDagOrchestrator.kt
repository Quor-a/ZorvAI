package com.ai.assistance.quro.core.cms

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.terminal.QuroTerminalBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * CMS v2 DAG 编排引擎（原创运行时 · 编排）。
 *
 * 把多个能力/命令按依赖关系编排执行：
 * - 拓扑分层，无依赖节点**并行**执行；
 * - 上游 stdout 作为下游 `${arg}` / `${<上游id>}` 输入（产物传递）；
 * - 任一节点超时/非零退出 → 中止后续未启动节点（回滚）。
 *
 * 安全（P0）：产物注入经 [shellQuote] 转义，杜绝把上游输出当 shell 代码执行（命令注入）。
 * 权限：编排入口应在调用前统一收集全图最高权限一次性确认（由上层 cms_orchestrate 工具负责）。
 */
data class CmsDagNode(
    val id: String,
    /** 命令模板（支持 ${arg} / ${<上游节点id>} 代入上游产物）。 */
    val action: String,
    val deps: List<String> = emptyList(),
    val timeoutSecs: Int = 30,
    val memMb: Int = 256,
)

data class CmsNodeOutcome(val id: String, val code: Int, val out: String)

data class CmsDagResult(
    val success: Boolean,
    val perNode: Map<String, CmsNodeOutcome>,
    val message: String,
    val aborted: List<String> = emptyList(),
)

object CmsDagOrchestrator {

    /** 拓扑分层（Kahn）。存在环或缺失依赖返回 null。 */
    fun levels(nodes: List<CmsDagNode>): List<List<CmsDagNode>>? {
        val byId = nodes.associateBy { it.id }
        if (nodes.any { it.deps.any { d -> d !in byId } }) return null
        val indeg = nodes.associateWith { it.deps.size }.toMutableMap()
        val remaining = nodes.toMutableList()
        val result = mutableListOf<List<CmsDagNode>>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { indeg[it] == 0 }
            if (ready.isEmpty()) return null // 环
            result.add(ready)
            ready.forEach { n ->
                remaining.remove(n)
                nodes.filter { it.deps.contains(n.id) }.forEach { succ -> indeg[succ] = indeg[succ]!! - 1 }
            }
        }
        return result
    }

    /**
     * 执行 DAG。返回每节点结果与中止列表。
     * 注意：每个节点为一次性命令（经 proot 执行）；回滚 = 不再启动依赖它的后续节点。
     */
    suspend fun execute(context: Context, nodes: List<CmsDagNode>): CmsDagResult = coroutineScope {
        val lv = levels(nodes) ?: return@coroutineScope CmsDagResult(
            false, emptyMap(), "⛔ DAG 存在环或缺失依赖，无法执行。",
        )
        val outputs = mutableMapOf<String, String>()
        val perNode = mutableMapOf<String, CmsNodeOutcome>()
        val aborted = mutableListOf<String>()
        var failed = false

        for (level in lv) {
            if (failed) {
                aborted.addAll(level.map { it.id })
                continue
            }
            val jobs = level.map { node ->
                async(Dispatchers.IO) {
                    val cmd = buildCommand(node, outputs)
                    val (code, out) = QuroTerminalBridge.run(context, cmd, node.timeoutSecs * 1000L)
                    CmsNodeOutcome(node.id, code, out)
                }
            }
            val results = jobs.awaitAll()
            results.forEach { r ->
                perNode[r.id] = r
                if (r.code == 0) outputs[r.id] = r.out else failed = true
            }
        }

        val success = !failed
        CmsDagResult(
            success,
            perNode,
            if (success) "✅ DAG 执行完成（${perNode.size} 节点）。" else "⛔ DAG 执行中止：${aborted.joinToString()}",
            aborted,
        )
    }

    /** 把上游产物按 ${<id>} / ${arg} 注入命令模板，并对产物做 shell 转义（防注入）。 */
    private fun buildCommand(node: CmsDagNode, outputs: Map<String, String>): String {
        var cmd = node.action
        node.deps.forEach { d ->
            cmd = cmd.replace("\${$d}", shellQuote(outputs[d] ?: ""))
        }
        if ("\${arg}" in cmd) {
            val merged = node.deps.joinToString("\n") { outputs[it] ?: "" }
            cmd = cmd.replace("\${arg}", shellQuote(merged))
        }
        return cmd
    }

    /** shell 单引号转义（P0 防命令注入）。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
