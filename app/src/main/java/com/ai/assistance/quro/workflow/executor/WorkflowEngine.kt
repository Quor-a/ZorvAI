package com.ai.assistance.quro.workflow.executor

import android.content.Context
// HttpCapability 已删除（WorkflowACI ACI 部分不移植）
import com.ai.assistance.quro.workflow.data.NotesRepository
import com.ai.assistance.quro.workflow.data.RunStore
import com.ai.assistance.quro.workflow.data.WorkflowRepository
import com.ai.assistance.quro.workflow.data.model.FlowNode
import com.ai.assistance.quro.workflow.data.model.NodeType
import com.ai.assistance.quro.workflow.data.model.Workflow
import com.ai.assistance.quro.workflow.platform.Device
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionType
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroLlmResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 工作流执行引擎（状态机解释器）。
 *
 * 把工作流当作一张有向图来解释执行：
 *  - 从 start 节点进入，沿 next / onError / branches / children 边推进；
 *  - 支持顺序、CONDITION 分支、SWITCH 多路、LOOP 循环、PARALLEL 并行扇出；
 *  - 每个节点执行失败可经 onError 边恢复；没有恢复则整次运行失败；
 *  - 变量（vars）贯穿全程，参数支持 ${var} 替换；CONDITION/LOOP 用表达式求值；
 *  - 运行过程写入 RunStore（runs.json），进程死亡后仍可回看；
 *  - 本地「运行」按钮与 ACI wf_trigger 共用同一入口。
 */
object WorkflowEngine {

    private lateinit var appCtx: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /** 入队一次运行（异步）。返回 runId，供调用方轮询 wf_run_status。 */
    fun run(wfId: String, inputs: Map<String, String> = emptyMap()): String {
        val runId = UUID.randomUUID().toString()
        scope.launch { execute(wfId, runId, inputs) }
        return runId
    }

    private suspend fun execute(wfId: String, runId: String, inputs: Map<String, String>) {
        val wf = WorkflowRepository.get(wfId)
        if (wf == null) {
            RunStore.start(runId, wfId, "?", inputs)
            RunStore.finish(runId, "failed", "workflow not found: $wfId", System.currentTimeMillis())
            return
        }
        if (!wf.enabled) {
            RunStore.start(runId, wfId, wf.name, inputs)
            RunStore.finish(runId, "failed", "workflow 已停用，未执行", System.currentTimeMillis())
            return
        }

        val nodeMap = wf.nodes.associateBy { it.id }
        val vars = LinkedHashMap<String, String>().apply {
            wf.variables.forEach { put(it.name, it.default) }
            putAll(inputs)
        }
        val log = StringBuilder()
        RunStore.start(runId, wfId, wf.name, inputs)
        log.appendLine("运行开始 · 输入: ${if (vars.isEmpty()) "(无)" else vars.entries.joinToString { "${it.key}=${it.value}" }}")

        val failed = execChain(wf.start.ifBlank { wf.nodes.firstOrNull()?.id }, vars, log, nodeMap, null, LinkedHashSet())

        val status = if (failed) "failed" else "success"
        val finishedAt = System.currentTimeMillis()
        log.appendLine(if (failed) "运行结束 · 失败" else "运行结束 · 成功")
        val logText = log.toString().trim()
        RunStore.finish(runId, status, logText, finishedAt)
        WorkflowRepository.updateRun(wfId, status, logText, runId)
    }

    /**
     * 从 startId 开始解释执行一条链，返回本次是否失败。
     * stopAt 非 null 时，遇到 next == stopAt 即停止（用于 LOOP/PARALLEL 子图回边）。
     */
    private suspend fun execChain(
        startId: String?,
        vars: MutableMap<String, String>,
        log: StringBuilder,
        nodeMap: Map<String, FlowNode>,
        stopAt: String?,
        visited: MutableSet<String>
    ): Boolean {
        var currentId = startId
        var localFailed = false
        var guard = 0
        val maxSteps = 5000

        while (currentId != null && currentId != stopAt) {
            if (guard++ > maxSteps) {
                log.appendLine("✗ 步数超过上限 $maxSteps，疑似死循环，已中止")
                return true
            }
            val node = nodeMap[currentId]
            if (node == null) {
                log.appendLine("✗ 节点不存在: $currentId")
                return true
            }
            if (!visited.add(currentId) && node.type != NodeType.LOOP) {
                log.appendLine("⚠ 检测到环（非 LOOP）: $currentId，已停止以避免死循环")
                break
            }

            val label = when (node.type) {
                NodeType.CONDITION -> "CONDITION ${node.params["expr"] ?: ""}"
                NodeType.SWITCH -> "SWITCH ${node.params["value"] ?: ""}"
                NodeType.LOOP -> "LOOP[${node.params["mode"] ?: "count"}]"
                NodeType.PARALLEL -> "PARALLEL ×${node.children.size}"
                NodeType.AI -> "AI ${(node.params["prompt"] ?: "").take(8)}"
                else -> node.type.value.uppercase()
            }
            log.appendLine("▶ $label (${node.id})")

            val outcome = runNode(node, vars, log)
            if (!outcome.ok) {
                log.appendLine("  ✗ ${node.type.value} 失败: ${outcome.error}")
                val errNext = node.onError
                if (errNext != null) {
                    log.appendLine("  ↳ onError → $errNext")
                    currentId = errNext
                    continue
                } else {
                    localFailed = true
                    break
                }
            }

            currentId = when (node.type) {
                NodeType.CONDITION -> {
                    val expr = node.params["expr"] ?: ""
                    val branch = if (Expression.eval(expr, vars)) "true" else "false"
                    log.appendLine("  CONDITION → $branch")
                    node.branches[branch] ?: node.branches["default"] ?: node.next
                }
                NodeType.SWITCH -> {
                    val value = Expression.substitute(node.params["value"] ?: "", vars)
                    log.appendLine("  SWITCH value=$value")
                    node.branches[value] ?: node.branches["default"] ?: node.next
                }
                NodeType.LOOP -> {
                    val mode = (node.params["mode"] ?: "count").lowercase()
                    val counterKey = "__loop_${node.id}"
                    val cnt = (vars[counterKey]?.toIntOrNull() ?: 0) + 1
                    vars[counterKey] = cnt.toString()
                    val cont = if (mode == "while") {
                        Expression.eval(node.params["while"] ?: "false", vars)
                    } else {
                        val limit = node.params["count"]?.toIntOrNull() ?: Int.MAX_VALUE
                        cnt <= limit
                    }
                    val branch = if (cont) "body" else "exit"
                    log.appendLine("  LOOP #$cnt ($mode) → $branch")
                    if (cont) {
                        val body = node.branches["body"] ?: node.children.firstOrNull()
                        if (body != null) {
                            if (execChain(body, vars, log, nodeMap, node.id, LinkedHashSet())) {
                                localFailed = true
                            }
                        }
                        node.id // 回到本节点继续循环
                    } else {
                        node.branches["exit"] ?: node.next
                    }
                }
                NodeType.PARALLEL -> {
                    val childVars = node.children.map { LinkedHashMap(vars) }
                    val results = withContext(Dispatchers.Default) {
                        node.children.mapIndexed { idx, childId ->
                            async {
                                execChain(childId, childVars[idx], log, nodeMap, node.id, LinkedHashSet())
                            }
                        }.awaitAll()
                    }
                    if (results.any { it }) localFailed = true
                    node.next
                }
                else -> node.next
            }
        }
        return localFailed
    }

    private data class Outcome(val ok: Boolean, val error: String = "")

    private suspend fun runNode(node: FlowNode, vars: MutableMap<String, String>, log: StringBuilder): Outcome {
        val p = node.params.mapValues { (_, v) -> Expression.substitute(v, vars) }
        return try {
            when (node.type) {
                NodeType.HTTP -> {
                    val url = p["url"] ?: throw IllegalArgumentException("HTTP 缺少 url")
                    val method = (p["method"] ?: "GET")
                    val headers = p["headers"] ?: "{}"
                    val body = p["body"] ?: ""
                    // 简易 HTTP 实现（替代 HttpCapability）
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = method.uppercase()
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    try {
                        val parsedHeaders = org.json.JSONObject(headers)
                        for (key in parsedHeaders.keys()) {
                            conn.setRequestProperty(key, parsedHeaders.getString(key))
                        }
                    } catch (_: Exception) {}
                    if (method.uppercase() in listOf("POST", "PUT", "PATCH")) {
                        conn.doOutput = true
                        conn.outputStream.write(body.toByteArray())
                    }
                    val code = conn.responseCode
                    val respBody = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val res = mapOf("status_code" to code.toString(), "response_body" to respBody)
                    log.appendLine("  HTTP ${method.uppercase()} $url → $code")
                    val outVar = p["out"]
                    if (outVar != null) {
                        vars[outVar] = respBody
                        log.appendLine("  HTTP 响应体 → 存入 \$$outVar (${respBody.length} 字符)")
                    }
                    Outcome(true)
                }
                NodeType.NOTE -> {
                    val text = p["text"] ?: ""
                    NotesRepository.add(text)
                    log.appendLine("  NOTE: $text")
                    Outcome(true)
                }
                NodeType.WAIT -> {
                    val ms = (p["ms"] ?: "0").toLongOrNull() ?: 0L
                    delay(ms)
                    log.appendLine("  WAIT ${ms}ms")
                    Outcome(true)
                }
                NodeType.OPEN_APP -> {
                    val pkg = p["package"] ?: throw IllegalArgumentException("OPEN_APP 缺少 package")
                    val ok = Device.launchApp(appCtx, pkg)
                    log.appendLine("  OPEN_APP $pkg → ${if (ok) "ok" else "未找到"}")
                    Outcome(ok)
                }
                NodeType.BROADCAST -> {
                    val action = p["action"] ?: throw IllegalArgumentException("BROADCAST 缺少 action")
                    val ordered = (p["ordered"] ?: "false").toBooleanStrictOrNull() ?: false
                    val ok = Device.sendBroadcast(appCtx, action, p["extras"], ordered)
                    log.appendLine("  BROADCAST $action → ${if (ok) "ok" else "失败"}")
                    Outcome(ok)
                }
                NodeType.NOTIFY -> {
                    Device.notify(appCtx, p["title"] ?: "工作流", p["body"] ?: "")
                    log.appendLine("  NOTIFY: ${p["title"]} / ${p["body"]}")
                    Outcome(true)
                }
                NodeType.FILE -> {
                    val out = Device.fileOp(appCtx, p["path"], p["mode"], p["content"])
                    val outVar = p["out"]
                    if (outVar != null) {
                        vars[outVar] = out
                        log.appendLine("  FILE ${p["mode"]} ${p["path"]} → 存入 \$$outVar (${out.length} 字符)")
                    } else {
                        log.appendLine("  FILE ${p["mode"]} ${p["path"]} (${out.length} 字符)")
                    }
                    Outcome(true)
                }
                // 控制流节点由 execChain 解释，此处无副作用
                NodeType.CONDITION, NodeType.SWITCH, NodeType.LOOP, NodeType.PARALLEL ->
                    Outcome(true)
                // 多媒体节点：本地 Intent + FileProvider（无新依赖）
                NodeType.OPEN_MEDIA -> {
                    val ok = Device.openMedia(appCtx, p["target"])
                    log.appendLine("  OPEN_MEDIA ${p["target"]} → ${if (ok) "已发起查看" else "失败/文件不存在"}")
                    Outcome(ok)
                }
                NodeType.PLAY_MEDIA -> {
                    val ok = Device.playMedia(appCtx, p["target"])
                    log.appendLine("  PLAY_MEDIA ${p["target"]} → ${if (ok) "已发起播放" else "失败/文件不存在"}")
                    Outcome(ok)
                }
                    NodeType.CAPTURE_PHOTO -> {
                    val ok = Device.capturePhoto(appCtx, p["path"])
                    log.appendLine("  CAPTURE_PHOTO → ${if (ok) "已调起相机" else "失败"}")
                    Outcome(ok)
                }
                // AI 节点：WorkflowACI 接入 ZorvAI 模型能力（「需要模型才能做到」的核心落点）。
                // 复用全局模型配置（QuroModelConfigRepository + 功能级覆盖），调用 QuroLlmClient.chat，
                // 把模型回复写入 out 变量，供后续节点（如 HTTP body）消费。
                NodeType.AI -> {
                    val prompt = p["prompt"] ?: throw IllegalArgumentException("AI 节点缺少 prompt")
                    val system = p["system"]
                    val base = QuroModelConfigRepository(appCtx).load()
                    if (base.apiKey.isBlank()) {
                        throw IllegalStateException("AI 节点需要模型 API Key，请先在「设置」配置模型（baseUrl/apiKey/model）")
                    }
                    val cfg = QuroFunctionModelConfigRepository(appCtx).resolveConfig(QuroFunctionType.CHAT, base)
                    val messages = buildList {
                        if (!system.isNullOrBlank()) add(QuroChatMessage("system", system))
                        add(QuroChatMessage("user", prompt))
                    }
                    val temp = p["temperature"]?.toFloatOrNull() ?: cfg.temperature
                    val maxT = p["maxTokens"]?.toIntOrNull() ?: cfg.maxTokens
                    log.appendLine("  AI 推理 model=${cfg.model} temp=$temp maxTokens=$maxT…")
                    val res = QuroLlmClient().chat(cfg.baseUrl, cfg.apiKey, cfg.model, messages, temp, maxT)
                    when (res) {
                        is QuroLlmResult.Text -> {
                            val outVar = p["out"]
                            if (outVar != null) {
                                vars[outVar] = res.content
                                log.appendLine("  AI 回复 → 存入 \$$outVar (${res.content.length} 字符)")
                            } else {
                                log.appendLine("  AI 回复: ${res.content.take(200)}")
                            }
                            Outcome(true)
                        }
                        is QuroLlmResult.Error -> Outcome(false, "AI 推理失败：${res.message}")
                        else -> Outcome(false, "AI 节点返回了不支持的结果类型")
                    }
                }
            }
        } catch (e: Exception) {
            Outcome(false, e.message ?: "unknown")
        }
    }
}
