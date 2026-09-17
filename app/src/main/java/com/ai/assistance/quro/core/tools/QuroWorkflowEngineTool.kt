package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.workflow.data.RunStore
import com.ai.assistance.quro.workflow.data.WorkflowRepository
import com.ai.assistance.quro.workflow.data.model.Workflow
import com.ai.assistance.quro.workflow.executor.WorkflowEngine
import org.json.JSONObject

/**
 * 工作流引擎 AI 侧调用入口（缺口清单 · 可实现项）。
 *
 *  - wf_create    用 Workflow JSON 创建/覆盖工作流，稳定 id = wf_<name>
 *  - wf_trigger   点燃（异步执行）已存在的工作流，返回 runId
 *  - wf_run_status 读取某次运行的 status + 日志尾部（供 AI 轮询）
 *
 * 与工具中心「节点编辑器」面板、本地「运行」按钮共用同一套 WorkflowRepository / WorkflowEngine，
 * 因此 AI 创建的 wf_<name> 也能在画布里直接看到、运行、编排。
 */

/** 创建/覆盖工作流。 */
class QuroWorkflowCreateTool : QuroTool {
    override val name = "wf_create"
    override val description =
        "创建一个工作流（同名覆盖）。参数 json 为工作流定义 JSON 字符串，结构：{name, trigger(manual|time|event), schedule, enabled, variables:[{name,default}], start, nodes:[{id,type,params,next,onError,branches,children}]}；也可直接把整个工作流对象作为 json 传入。返回工作流 id（wf_<name>）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "json":{"type":"string","description":"工作流定义 JSON 字符串"}
        },
        "required":["json"]
    }"""
    override fun run(context: Context, arguments: String): String {
        return try {
            val arg = JSONObject(arguments)
            val raw = arg.optString("json").takeIf { it.isNotBlank() } ?: arguments
            val o = JSONObject(raw)
            val name = o.optString("name").ifBlank { arg.optString("name").ifBlank { "untitled" } }
            val id = o.optString("id").ifBlank { "wf_$name" }
            o.put("id", id)
            o.put("name", name)
            val wf = Workflow.fromJson(o)
            WorkflowRepository.upsert(wf)
            "已创建工作流 id=$id name=${wf.name} 节点数=${wf.nodes.size} 起始节点=${wf.start}"
        } catch (e: Exception) {
            "wf_create 失败: ${e.message}"
        }
    }
}

/** 点燃（异步执行）已存在的工作流。 */
class QuroWorkflowTriggerTool : QuroTool {
    override val name = "wf_trigger"
    override val description =
        "点燃（立即异步执行）一个已存在的工作流，返回 runId。参数 id（wf_<name>）或 name 二选一。执行后用 wf_run_status 传入 runId 轮询进度与结果。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "id":{"type":"string","description":"工作流 id（wf_<name>），与 name 二选一"},
            "name":{"type":"string","description":"工作流名（按名查找 id），与 id 二选一"}
        }
    }"""
    override fun run(context: Context, arguments: String): String {
        return try {
            val arg = JSONObject(arguments)
            val wfId = when {
                arg.optString("id").isNotBlank() -> arg.optString("id")
                arg.optString("name").isNotBlank() -> {
                    val n = arg.optString("name")
                    WorkflowRepository.getAll().firstOrNull { it.name == n }?.id ?: "wf_$n"
                }
                else -> ""
            }.takeIf { it.isNotBlank() } ?: return "wf_trigger 失败：缺少 id 或 name"
            val wf = WorkflowRepository.get(wfId)
                ?: return "wf_trigger 失败：找不到工作流 $wfId（请先用 wf_create 创建）"
            if (!wf.enabled) return "wf_trigger 失败：工作流 $wfId 已停用"
            val runId = WorkflowEngine.run(wfId, emptyMap())
            "已点燃工作流 $wfId，runId=$runId（用 wf_run_status 查询进度与结果）"
        } catch (e: Exception) {
            "wf_trigger 失败: ${e.message}"
        }
    }
}

/** 查询某次运行的实时状态与日志尾部。 */
class QuroWorkflowRunStatusTool : QuroTool {
    override val name = "wf_run_status"
    override val description =
        "查询某次工作流运行的实时状态与日志尾部。参数 runId（来自 wf_trigger 返回值）。返回 status（running|success|failed）+ 最近日志。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "runId":{"type":"string","description":"运行 id（wf_trigger 返回的 runId）"}
        },
        "required":["runId"]
    }"""
    override fun run(context: Context, arguments: String): String {
        return try {
            val arg = JSONObject(arguments)
            val runId = arg.optString("runId").takeIf { it.isNotBlank() }
                ?: return "wf_run_status 失败：缺少 runId"
            val rec = RunStore.get(runId)
                ?: return "wf_run_status 失败：找不到运行记录 $runId（runId 错误或运行记录已被清理）"
            val tail = rec.log.lines().filter { it.isNotBlank() }.takeLast(15).joinToString("\n")
            "工作流「${rec.wfName}」运行状态=${rec.status}\n${if (tail.isBlank()) "(暂无日志)" else tail}"
        } catch (e: Exception) {
            "wf_run_status 失败: ${e.message}"
        }
    }
}
