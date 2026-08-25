package com.ai.assistance.quro.workflow.data.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 节点类型（动作节点 + 控制流节点）。
 *
 * 动作节点：
 *  - HTTP      代发 HTTP 请求（url / method / headers / body）
 *  - NOTE      写入一条备注（text）
 *  - WAIT      延时等待（ms）
 *  - OPEN_APP  启动应用（package）
 *  - BROADCAST 发送广播（action / extras / ordered）
 *  - NOTIFY    本地通知（title / body）
 *  - FILE      读写文件（path / mode / content / out）
 *
 * 控制流节点（由引擎解释，不直接执行副作用）：
 *  - CONDITION 条件分支（expr → branches["true"] / branches["false"]）
 *  - SWITCH    多路分支（value → branches[key]）
 *  - LOOP      循环（mode=count|while → branches["body"] / branches["exit"]）
 *  - PARALLEL  并行扇出（children 列表并发执行）
 *
 * 多媒体节点（本地 Intent + FileProvider，无新依赖）：
 *  - OPEN_MEDIA    打开/查看媒体（target=URL 或应用私有文件路径）
 *  - PLAY_MEDIA    播放音频/视频（target=URL 或应用私有文件路径）
 *  - CAPTURE_PHOTO 调起系统相机拍照（path=保存路径，默认 captured_photo.jpg）
 *  - AI           调用 ZorvAI 模型推理（prompt / system / out）：WorkflowACI 接入现模型能力，
 *                 使工作流能「用模型干活」（如生成文案、总结、决策），结果写入 out 变量供后续节点消费。
 *
 * 节点 JSON 形态（与 ACI wf_create 的 graph.nodes 同构）：
 *   {"id":"n1","type":"http","url":"...","method":"POST",
 *    "next":"n2","onError":"n_err"}
 */
enum class NodeType(val value: String) {
    HTTP("http"),
    NOTE("note"),
    WAIT("wait"),
    OPEN_APP("open_app"),
    BROADCAST("broadcast"),
    NOTIFY("notify"),
    FILE("file"),
    CONDITION("condition"),
    SWITCH("switch"),
    LOOP("loop"),
    PARALLEL("parallel"),
    OPEN_MEDIA("open_media"),
    PLAY_MEDIA("play_media"),
    CAPTURE_PHOTO("capture_photo"),
    AI("ai");

    companion object {
        fun from(v: String?): NodeType = when (v?.lowercase()) {
            "note" -> NOTE
            "wait" -> WAIT
            "open_app", "openapp", "app" -> OPEN_APP
            "broadcast" -> BROADCAST
            "notify", "notification" -> NOTIFY
            "file" -> FILE
            "condition", "if" -> CONDITION
            "switch", "case" -> SWITCH
            "loop", "for", "while" -> LOOP
            "parallel", "fanout" -> PARALLEL
            "open_media", "media", "openmedia" -> OPEN_MEDIA
            "play_media", "play", "media_play" -> PLAY_MEDIA
            "capture_photo", "photo", "capture", "camera" -> CAPTURE_PHOTO
            "ai", "llm", "model", "gpt" -> AI
            else -> HTTP
        }
    }
}

/** 工作流级变量（运行前可注入默认值，运行时可被覆盖）。 */
data class Variable(val name: String, val default: String = "") {
    fun toJson(): JSONObject = JSONObject().apply { put("name", name); put("default", default) }
    companion object {
        fun fromJson(o: JSONObject): Variable =
            Variable(o.optString("name"), o.optString("default"))
    }
}

/**
 * 流程节点：一个图顶点。
 *
 *  - params    动作参数（键值对；支持 ${var} 变量替换）
 *  - next      默认后继节点 id（顺序 / 动作完成后）
 *  - onError   本节点执行失败时的跳转节点 id
 *  - branches  条件 / 分支的目标映射（condition: "true"/"false"；switch: 取值键）
 *  - children  并行节点的子节点 id 列表
 */
data class FlowNode(
    val id: String,
    val type: NodeType,
    val params: Map<String, String> = emptyMap(),
    val next: String? = null,
    val onError: String? = null,
    val branches: Map<String, String> = emptyMap(),
    val children: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.value)
        params.forEach { (k, v) -> put(k, v) }
        next?.let { put("next", it) }
        onError?.let { put("onError", it) }
        if (branches.isNotEmpty()) {
            val b = JSONObject()
            branches.forEach { (k, v) -> b.put(k, v) }
            put("branches", b)
        }
        if (children.isNotEmpty()) {
            val c = JSONArray()
            children.forEach { c.put(it) }
            put("children", c)
        }
    }

    companion object {
        private val META = setOf("id", "type", "next", "onError", "branches", "children")

        fun fromJson(o: JSONObject): FlowNode {
            val type = NodeType.from(o.optString("type"))
            val params = mutableMapOf<String, String>()
            o.keys().forEach { k -> if (k !in META) params[k] = o.optString(k) }
            val next = o.optString("next").ifBlank { null }
            val onError = o.optString("onError").ifBlank { null }
            val branches = mutableMapOf<String, String>()
            o.optJSONObject("branches")?.let { b ->
                b.keys().forEach { k -> branches[k] = b.optString(k) }
            }
            val children = mutableListOf<String>()
            o.optJSONArray("children")?.let { c ->
                for (i in 0 until c.length()) children.add(c.optString(i))
            }
            return FlowNode(o.optString("id"), type, params, next, onError, branches, children)
        }
    }
}

/**
 * 工作流实体（图模型）。
 *
 *  - trigger   manual（手动）| time（定时）| event（事件）
 *  - schedule  time:  "daily:HH:mm" | "interval:Nm" | "cron:m h dom mon dow"
 *              event: "boot" | "user_present" | "screen_on" | "screen_off" | "connectivity"
 *  - variables 运行前注入的变量
 *  - start     起始节点 id
 *  - nodes     节点列表（图顶点集合）
 */
data class Workflow(
    val id: String,
    val name: String,
    val trigger: String,            // manual | time | event
    val schedule: String,
    val enabled: Boolean = true,
    val variables: List<Variable> = emptyList(),
    val start: String = "",
    val nodes: List<FlowNode> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastRun: Long = 0L,
    val lastStatus: String = "idle", // idle | running | success | failed
    val lastLog: String = "",
    val lastRunId: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("trigger", trigger)
        put("schedule", schedule)
        put("enabled", enabled)
        val vars = JSONArray()
        variables.forEach { vars.put(it.toJson()) }
        put("variables", vars)
        put("start", start)
        val ns = JSONArray()
        nodes.forEach { ns.put(it.toJson()) }
        put("nodes", ns)
        put("createdAt", createdAt)
        put("lastRun", lastRun)
        put("lastStatus", lastStatus)
        put("lastLog", lastLog)
        put("lastRunId", lastRunId)
    }

    companion object {
        fun fromJson(o: JSONObject): Workflow {
            // 兼容旧 trigger 取值（once/recurring）
            val rawTrigger = o.optString("trigger", "manual")
            val trigger = when (rawTrigger) {
                "recurring" -> "time"
                "once" -> "manual"
                else -> rawTrigger
            }
            val vars = mutableListOf<Variable>()
            o.optJSONArray("variables")?.let { a ->
                for (i in 0 until a.length()) vars.add(Variable.fromJson(a.getJSONObject(i)))
            }
            val nodes = mutableListOf<FlowNode>()
            val rawNodes = o.opt("nodes")
            when {
                rawNodes is JSONArray -> for (i in 0 until rawNodes.length())
                    nodes.add(FlowNode.fromJson(rawNodes.getJSONObject(i)))
                rawNodes is String -> runCatching {
                    val a = JSONArray(rawNodes)
                    for (i in 0 until a.length()) nodes.add(FlowNode.fromJson(a.getJSONObject(i)))
                }
                // 兼容旧版导出：actions 为 JSON 数组字符串（线性串联为链）
                else -> {
                    val legacy = o.optString("actions").takeIf { it.isNotBlank() }
                    if (legacy != null) {
                        runCatching {
                            val a = JSONArray(legacy)
                            for (i in 0 until a.length()) {
                                val no = a.getJSONObject(i)
                                val t = NodeType.from(no.optString("type"))
                                val p = mutableMapOf<String, String>()
                                no.keys().forEach { k -> if (k != "type") p[k] = no.optString(k) }
                                nodes.add(
                                    FlowNode(
                                        id = "n$i",
                                        type = t,
                                        params = p,
                                        next = if (i + 1 < a.length()) "n${i + 1}" else null
                                    )
                                )
                            }
                        }
                    }
                }
            }
            return Workflow(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name"),
                trigger = trigger,
                schedule = o.optString("schedule"),
                enabled = o.optBoolean("enabled", true),
                variables = vars,
                start = o.optString("start").ifBlank { nodes.firstOrNull()?.id ?: "" },
                nodes = nodes,
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                lastRun = o.optLong("lastRun", 0L),
                lastStatus = o.optString("lastStatus", "idle"),
                lastLog = o.optString("lastLog", ""),
                lastRunId = o.optString("lastRunId")
            )
        }

        /**
         * 从旧式线性 actions 数组构建图（动作顺序串联为链）。
         * actions: [{"type":"http","url":"..."}, ...]
         */
        fun fromLinear(
            name: String,
            trigger: String,
            schedule: String,
            enabled: Boolean,
            actionsJson: String
        ): Workflow {
            val arr = runCatching { JSONArray(actionsJson) }.getOrDefault(JSONArray())
            val nodes = mutableListOf<FlowNode>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = NodeType.from(o.optString("type"))
                val params = mutableMapOf<String, String>()
                o.keys().forEach { k -> if (k != "type") params[k] = o.optString(k) }
                nodes.add(
                    FlowNode(
                        id = "n$i",
                        type = type,
                        params = params,
                        next = if (i + 1 < arr.length()) "n${i + 1}" else null
                    )
                )
            }
            return Workflow(
                id = UUID.randomUUID().toString(),
                name = name,
                trigger = when (trigger) {
                    "recurring" -> "time"
                    "once" -> "manual"
                    else -> trigger
                },
                schedule = schedule,
                enabled = enabled,
                nodes = nodes,
                start = nodes.firstOrNull()?.id ?: ""
            )
        }
    }
}
