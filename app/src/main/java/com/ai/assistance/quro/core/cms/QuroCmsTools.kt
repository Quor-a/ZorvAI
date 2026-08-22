package com.ai.assistance.quro.core.cms

import android.content.Context
import com.ai.assistance.quro.core.cms.AuthorizationLevel
import com.ai.assistance.quro.core.cms.CmsDagNode
import com.ai.assistance.quro.core.cms.CmsDagOrchestrator
import com.ai.assistance.quro.core.cms.CmsExecutionEngine
import com.ai.assistance.quro.core.cms.CmsExecResult
import com.ai.assistance.quro.core.cms.CmsStateStore
import com.ai.assistance.quro.core.cms.QuroCmsBroker
import com.ai.assistance.quro.core.policy.QuroPolicy
import com.ai.assistance.quro.core.policy.QuroPolicyStore
import com.ai.assistance.quro.core.tools.QuroTool
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * CMS v2 对 LLM 暴露的可调用工具（原创）：让 AI「自我感知」自己装了哪些能力模块、能调用什么，
 * 并能真正执行（而非只是文本描述）。策略门控来自 [QuroPolicyStore]：
 * - ALLOW：直接执行，不再询问。
 * - DENY：直接拒绝。
 * - ASK：返回需要用户在对话底部控制条切到「允许」的提示（保持每次都问）。
 */
class QuroCmsListTool : QuroTool {
    override val name = "cms_list"
    override val description =
        "列出当前已安装的所有 CMS v2 能力模块及其可调用的能力（含 id、说明、风险等级）。当用户问「你能做什么 / 有什么模块 / 有哪些能力」时使用。参数为空 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val repo = QuroCmsRepository(context)
        val caps = repo.loadCapabilities()
        if (caps.isEmpty()) return "当前没有已安装的 CMS v2 能力模块。"
        val sb = StringBuilder("已安装的 CMS v2 能力模块：\n")
        caps.forEach { (m, c) ->
            val risk = c.requiresPermissions.mapNotNull { m.findPermission(it)?.level?.name }.distinct()
                .joinToString("/").ifBlank { "Normal" }
            val hosts = c.runOn.joinToString("/") { it.label }
            sb.append("- [${m.name}] ${c.id}：${c.summary}（风险级别：$risk 宿主：$hosts）\n")
        }
        val policy = QuroPolicyStore.getCms(context)
        sb.append("\n当前 CMS v2 权限模式：${policy.name}（允许/禁止/询问）。用 cms_call 调用具体能力。")
        return sb.toString().trim()
    }
}

class QuroCmsCallTool : QuroTool {
    override val name = "cms_call"
    override val description =
        "调用一个 CMS v2 能力模块暴露的应用内能力（如 run_node / device_model / open_url / web_search）。" +
            "参数：{\"capability_id\":\"能力id\",\"args\":{参数名:参数值}}。会经过权限策略门控，通过四类受控通道执行（intent 拉起其他 App / js QuickJS 沙箱 / api 应用内只读 / terminal proot-Alpine 沙箱）。部分能力声明 Elevated(Shizuku 已连) 或 Critical(ROOT 可用) 权限级别，仅在对应系统授权已授予时才放行，否则返回引导提示；不直接执行裸 shell 或无障碍自动化。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "capability_id":{"type":"string","description":"能力 id，例如 echo_text / device_info / run_code_dual"},
            "args":{"type":"object","description":"能力所需参数，键为 ${'$'}{参数名}，例如 {\"text\":\"hello\"}"},
            "target":{"type":"string","description":"运行宿主：auto(默认,按电量/锁屏/proot就绪自动选)/app(前端应用内)/terminal(后端 proot 终端)。能力声明了 runOn 才支持对应宿主"}
        },
        "required":["capability_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val repo = QuroCmsRepository(context)
        CmsStateStore.init(context)
        val obj = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON：$arguments" }
        val capId = obj.optString("capability_id", "").trim()
        if (capId.isEmpty()) return "缺少 capability_id 参数。"

        val pair = repo.loadCapabilities().firstOrNull { it.second.id == capId }
            ?: return "未找到能力：$capId（先用 cms_list 查看可用能力）。"
        val (module, cap) = pair

        val argsMap = mutableMapOf<String, String>()
        val argsObj = obj.optJSONObject("args")
        argsObj?.keys()?.forEach { k -> argsMap[k] = argsObj.optString(k, "") }

        val target = InvocationTarget.parse(obj.optString("target", "auto"))
        val resolution = CmsHostRouter.resolve(cap, target, context)
        if (resolution.host == null) {
            return resolution.guidance ?: "⛔ 无法解析运行宿主"
        }
        val host = resolution.host

        val policy = QuroPolicyStore.getCms(context)
        val taskId = CmsStateStore.newTask("call", "$capId")
        val result: CmsExecResult = when (policy) {
            QuroPolicy.DENY -> {
                CmsStateStore.finishTask(taskId, false, "⛔ CMS 权限模式=禁止")
                CmsExecResult(false, 1, "⛔ CMS v2 权限模式=禁止：该能力被策略禁用，无法执行。", "", emptyList(), 0, taskId)
            }
            QuroPolicy.ASK -> runBlocking {
                val res = QuroCmsExecutor(context).execute(
                    module = module, cap = cap, args = argsMap, uiRequest = { null }, host = host,
                )
                if (res.startsWith("⛔ 权限被拒绝")) {
                    CmsStateStore.finishTask(taskId, false, "需授权（询问模式被拦截）")
                    CmsExecResult(
                        false, 1,
                        "⚠️ CMS v2 权限模式=询问：该能力需要授权，已被策略拦截。" +
                            "请在对话底部「CMS 权限模式」控制条切到「允许」，或授予对应权限后重试。",
                        "", emptyList(), 0, taskId,
                    )
                } else {
                    val ok = !res.startsWith("⛔")
                    CmsStateStore.finishTask(taskId, ok, res.take(200))
                    CmsExecResult(ok, if (ok) 0 else 1, res, "", emptyList(), 0, taskId)
                }
            }
            QuroPolicy.ALLOW -> runBlocking {
                CmsExecutionEngine.execute(context, module, cap, argsMap, { AuthorizationLevel.Temporary }, target, taskId)
            }
        }
        // 返回结构化结果 + task_id，便于 AI 后续用 cms_result / cms_logs 回查（反馈环）
        return buildString {
            append(result.stdout)
            if (!result.stdout.endsWith("\n")) append("\n")
            append("\n[CMS 执行反馈] task_id=${result.taskId} 状态=${if (result.ok) "success" else "failed"} 耗时=${result.durationMs}ms")
            append("\n[宿主] ${host.label}（target=${target.label}）")
            append("\n可用 cms_status / cms_logs / cms_result 查询进度与结构化结果。")
        }.trimEnd()
    }
}

/**
 * CMS v2 状态查询工具（原创 · 反馈环）：让 AI 确认「部署/调用是否成功」。
 * 返回各模块部署态 + 最近任务终态；指定 module_id 则返回该模块部署态与日志。
 */
class QuroCmsStatusTool : QuroTool {
    override val name = "cms_status"
    override val description =
        "查询 CMS v2 各模块与最近任务的执行状态（部署态/运行中/任务终态），让 AI 确认「部署/调用是否成功」。" +
            "参数：{} 查全局摘要，或 {\"module_id\":\"模块id\"} 查指定模块部署态与日志。"
    override val parametersJson = """{"type":"object","properties":{"module_id":{"type":"string","description":"可选，指定模块 id 查看其部署态"}}}"""

    override fun run(context: Context, arguments: String): String {
        CmsStateStore.init(context)
        val obj = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val mid = obj.optString("module_id", "").trim()
        return if (mid.isNotBlank()) {
            val m = CmsStateStore.getModule(mid)
            buildString {
                append("模块 $mid 状态：\n")
                if (m != null) {
                    append("- 部署态=${m.deployStatus} 运行中=${m.running}")
                    if (m.lastDeployAt > 0)
                        append(" 最近部署=${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(m.lastDeployAt))}")
                    if (m.lastError.isNotBlank()) append(" 错误=${m.lastError}")
                    append("\n")
                } else append("- 暂无记录（未部署/未执行）\n")
                append("\n日志：\n" + CmsStateStore.getLogs(mid))
            }.trim()
        } else {
            CmsStateStore.summary()
        }
    }
}

/**
 * CMS v2 日志读取工具（原创 · 反馈环）：读取某模块或某任务的执行日志（含进度行/stdout），
 * 用于确认执行过程。
 */
class QuroCmsLogsTool : QuroTool {
    override val name = "cms_logs"
    override val description =
        "读取某模块或某任务的执行日志（含进度行/stdout），用于确认执行过程。参数：{\"module_id\":\"模块id\"} 或 {\"task_id\":\"任务id\"}。"
    override val parametersJson = """{"type":"object","properties":{"module_id":{"type":"string"},"task_id":{"type":"string"}}}"""

    override fun run(context: Context, arguments: String): String {
        CmsStateStore.init(context)
        val obj = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON" }
        val mid = obj.optString("module_id", "").trim()
        val tid = obj.optString("task_id", "").trim()
        return when {
            mid.isNotBlank() -> "模块 $mid 日志：\n" + CmsStateStore.getLogs(mid)
            tid.isNotBlank() -> {
                val t = CmsStateStore.getTask(tid)
                if (t == null) "⛔ 未找到任务：$tid" else buildString {
                    append("任务 $tid [${t.kind}] ${t.target} → ${t.status}\n")
                    append("耗时=${t.durationMs}ms 退出码=${t.exitCode}\n")
                    append("消息：${t.message}\n")
                    append("stdout:\n${t.stdout.take(4000)}\n")
                    if (t.stderr.isNotBlank()) append("stderr:\n${t.stderr}\n")
                }.trim()
            }
            else -> "请提供 module_id 或 task_id。"
        }
    }
}

/**
 * CMS v2 结构化结果读取工具（原创 · 反馈环）：读取一次执行的**结构化结果**
 * （是否成功/退出码/stdout/stderr/耗时），由 cms_call 返回的 task_id 指定。
 */
class QuroCmsResultTool : QuroTool {
    override val name = "cms_result"
    override val description =
        "读取一次执行的**结构化结果**（是否成功/退出码/stdout/stderr/耗时），由 cms_call 返回的 task_id 指定。参数：{\"task_id\":\"任务id\"}。"
    override val parametersJson = """{"type":"object","properties":{"task_id":{"type":"string","description":"cms_call 返回的任务 id"}},"required":["task_id"]}"""

    override fun run(context: Context, arguments: String): String {
        CmsStateStore.init(context)
        val obj = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON" }
        val tid = obj.optString("task_id", "").trim()
        if (tid.isEmpty()) return "缺少 task_id 参数。"
        val t = CmsStateStore.getTask(tid) ?: return "⛔ 未找到任务：$tid（可能已过期或任务 id 错误）。"
        return buildString {
            append("任务 ${t.taskId} 结果：\n")
            append("状态=${t.status} 成功=${t.status == "success"}\n")
            append("退出码=${t.exitCode} 耗时=${t.durationMs}ms\n")
            append("消息=${t.message}\n")
            append("stdout=\n${t.stdout}\n")
            if (t.stderr.isNotBlank()) append("stderr=\n${t.stderr}\n")
        }.trim()
    }
}

/**
 * CMS v2 DAG 编排工具（原创 · 反馈环）：按依赖编排执行一组 terminal 命令（DAG）。
 * 无依赖节点并行、上游 stdout 作为下游输入、任一失败中止后续，结果落状态系统。
 */
class QuroCmsRunDagTool : QuroTool {
    override val name = "cms_run_dag"
    override val description =
        "按依赖编排执行一组 terminal 命令（DAG）：无依赖并行、上游 stdout 作为下游输入、任一失败中止后续。" +
            "参数：{\"nodes\":[{\"id\":\"a\",\"action\":\"echo hi\",\"deps\":[],\"timeout_secs\":30}]}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "nodes":{"type":"array","description":"节点数组，每项含 id / action / deps(上游id列表) / timeout_secs"}
        },
        "required":["nodes"]
    }"""

    override fun run(context: Context, arguments: String): String {
        CmsStateStore.init(context)
        val obj = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON" }
        val arr = obj.optJSONArray("nodes") ?: return "缺少 nodes 数组。"
        val nodes = mutableListOf<CmsDagNode>()
        for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i)
            nodes.add(
                CmsDagNode(
                    id = n.optString("id", "n$i"),
                    action = n.optString("action", ""),
                    deps = jsonToStringList(n.optJSONArray("deps")),
                    timeoutSecs = n.optInt("timeout_secs", 30),
                )
            )
        }
        if (nodes.isEmpty()) return "nodes 为空。"
        val tid = CmsStateStore.newTask("run_dag", "${nodes.size}节点")
        CmsStateStore.updateTask(tid, 5, "编排执行 ${nodes.size} 个节点")
        return runBlocking {
            val res = CmsDagOrchestrator.execute(context, nodes)
            CmsStateStore.finishTask(
                tid, res.success, res.message,
                stdout = res.perNode.entries.joinToString("\n") { "${it.key}: ${it.value.out.take(500)}" },
            )
            buildString {
                append(res.message)
                append("\n")
                res.perNode.forEach { (id, o) -> append("- $id → exit ${o.code}: ${o.out.take(500)}\n") }
                if (res.aborted.isNotEmpty()) append("中止节点：${res.aborted.joinToString()}\n")
            }.trim()
        }
    }
}

/** JSONArray -> List<String>（空安全）。 */
private fun jsonToStringList(a: JSONArray?): List<String> {
    if (a == null) return emptyList()
    return (0 until a.length()).mapNotNull { runCatching { a.getString(it) }.getOrNull() }
}

/**
 * 特权通道状态查询工具（原创）：让 AI 在调用高风险能力前，先自查当前各特权通道的可用性
 * （L1 无障碍 / L2 Shizuku / L3 设备管理员 / L4 Root），以及 CMS v2 权限模式与已授权项，
 * 据此「自己决定用哪个通道」，而非盲目调用被拦截。
 */
class QuroPrivStatusTool : QuroTool {
    override val name = "priv_status"
    override val description =
        "查看 CMS v2 权限模式与已授权项。能力经四类受控通道执行（intent 拉起其他 App / js QuickJS 沙箱 / api 应用内只读 / terminal proot-Alpine 沙箱）；部分能力可声明要求 Shizuku(已连)/ROOT(可用) 授权，由权限中枢按系统授权状态自动闸控放行或拒绝引导，不直接执行裸 shell 或无障碍自动化。参数为空 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val sb = StringBuilder("CMS v2 执行架构：能力经四类受控通道执行（intent 拉起其他 App / js QuickJS 沙箱 / api 应用内只读 / terminal proot-Alpine 沙箱）。部分能力声明 Elevated(Shizuku 已连) 或 Critical(ROOT 可用) 权限级别，须对应系统授权已授予才由权限中枢放行，否则拒绝并给出引导；不直接跑裸 shell 或无障碍自动化控制系统。\n")
        sb.append("CMS v2 权限模式 = ${QuroPolicyStore.getCms(context).name}\n")
        val auths = QuroCmsBroker(context).listAuths()
            .filter { it.level != AuthorizationLevel.Denied }
        if (auths.isNotEmpty()) {
            sb.append("\n已授权 CMS 权限：\n")
            auths.forEach { sb.append("- ${it.moduleId}/${it.permissionId} = ${it.level.name}\n") }
        }
        val caps = QuroCmsRepository(context).loadCapabilities()
        if (caps.isNotEmpty()) {
            sb.append("\n可用能力（共 ${caps.size} 项）：\n")
            caps.take(30).forEach { (m, c) -> sb.append("- ${c.id}：${c.summary}\n") }
        }
        sb.append("\n提示：cms_call 可带 target=auto/app/terminal 指定运行宿主（auto 按电量/锁屏/proot 就绪自动选前端或后端）；cms_deploy_terminal 把模块部署到 proot 终端并运行。")
        return sb.toString().trim()
    }
}

/**
 * CMS v2 终端部署工具（原创）：把一个 [CmsDeployPackage] 推到 proot/Alpine 沙箱（/root/cms/<id>），
 * 写文件 + 装依赖(apk/pip) + 准备启动。
 *
 * 安全闸（P0）：部署是高危动作，**必须显式 confirm=true** 才执行；AI 须在用户确认后传入，
 * 不允许静默 auto-available 执行。未带 confirm 仅返回引导说明。
 */
class QuroCmsDeployTool : QuroTool {
    override val name = "cms_deploy_terminal"
    override val description =
        "把一个能力模块部署到 proot/Alpine 终端沙箱（/root/cms/<id>），写文件并安装依赖(apk/pip)，使其可在 Linux 环境运行。" +
            "参数：{\"module_id\":\"模块id（如 demo-py）\" 或 \"package\":\"CmsDeployPackage 的 JSON（须带 sha256 签名）\", \"confirm\":true}。" +
            "⚠️ 部署为高危动作，必须 confirm=true 才执行；首次部署建议用户在 CMS 界面点「部署到终端」按钮确认。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "module_id":{"type":"string","description":"内置示例模块 id，如 demo-py（部署一个最小 python3 演示模块）"},
            "package":{"type":"string","description":"完整 CmsDeployPackage JSON（含 sha256 完整性校验），用于部署自定义模块"},
            "confirm":{"type":"boolean","description":"必须为 true 才执行部署；否则仅返回说明"}
        }
    }"""

    override fun run(context: Context, arguments: String): String {
        val obj = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON：$arguments" }
        val confirm = obj.optBoolean("confirm", false)
        if (!confirm) {
            return "⚠️ cms_deploy_terminal 为高危动作，需 confirm=true 才执行。请在用户明确确认后重试（或让用户点 CMS 界面的「部署到终端」按钮）。"
        }
        val pkg: CmsDeployPackage = when {
            obj.optString("package", "").isNotBlank() -> {
                val p = runCatching { CmsDeployPackage.fromJson(obj.optString("package")) }
                    .getOrElse { return "⛔ package 不是合法 CmsDeployPackage JSON：${it.message}" }
                if (!p.verifyIntegrity()) return "⛔ 部署包完整性校验失败（sha256 不匹配），拒绝部署。"
                p
            }
            obj.optString("module_id", "").isNotBlank() -> {
                // 内置示例；真实模块的包由 cms-package.json 携带，后续从模块读取。
                when (obj.optString("module_id").trim()) {
                    "demo-py" -> CmsDeployPackage.samplePython()
                    else -> return "⛔ 未知内置模块：${obj.optString("module_id")}（自定义模块请用 package 字段传入签名 JSON）"
                }
            }
            else -> return "缺少 module_id 或 package 参数。"
        }
        return CmsTerminalDeployer.deploy(context, pkg)
    }
}

/**
 * CMS v2 终端卸载工具（原创）：删除已部署到 proot 终端的模块目录。
 */
class QuroCmsUndeployTool : QuroTool {
    override val name = "cms_undeploy_terminal"
    override val description =
        "卸载已部署到 proot 终端的 CMS 模块（删除 /root/cms/<id> 目录）。参数：{\"module_id\":\"模块id\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "module_id":{"type":"string","description":"要卸载的模块 id"}
        },
        "required":["module_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val obj = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON：$arguments" }
        val moduleId = obj.optString("module_id", "").trim()
        if (moduleId.isEmpty()) return "缺少 module_id 参数。"
        return CmsTerminalDeployer.undeploy(context, moduleId)
    }
}

/**
 * CMS 引擎（系统资源包）状态查询工具（原创）：让 AI 回查「CMS 引擎」这个一级运行引擎的
 * 部署就绪态/健康/版本/共享服务/部署进度/日志。对应 [CmsEngineStore]（区别于 [cms_status] 查的模块态）。
 */
class QuroCmsEngineStatusTool : QuroTool {
    override val name = "cms_engine_status"
    override val description =
        "查询「CMS 引擎（系统资源包）」的部署就绪态、健康度、版本、拉起的共享服务（NODE/PYTHON/SSH/JAVA/RUST/GO 等运行时）、部署进度与日志。" +
            "这是 CMS 的一级运行引擎（区别于能力模块），是模块运行的基础底座。当用户问「CMS 引擎状态/部署好没/引擎就绪了吗/引擎拉起了哪些服务」时使用。参数为空 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        CmsEngineStore.init(context)
        val s = CmsEngineStore.snapshot.value
        val df = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val lastDeploy = if (s.lastDeployAt > 0) df.format(java.util.Date(s.lastDeployAt)) else "从未"
        val deploying = if (s.deploying) "（部署中… 当前步骤：${s.deployStep}）" else ""
        val status = when {
            s.ready && s.health -> "● 就绪且健康"
            s.ready && !s.health -> "○ 已部署但健康检查未通过"
            s.deploying -> "◐ 部署中"
            else -> "○ 未部署"
        }
        return buildString {
            append("CMS 引擎（系统资源包）状态：$status$deploying\n")
            append("- 版本：${if (s.engineVersion.isNotBlank()) s.engineVersion else "未部署"}\n")
            append("- 就绪=$s.ready 健康=$s.health\n")
            append("- 共享服务（运行时）：${if (s.services.isNotEmpty()) s.services.joinToString(" / ") else "无（未就绪）"}\n")
            append("- 最近部署时间：$lastDeploy\n")
            if (s.lastError.isNotBlank()) append("- 最近错误：${s.lastError}\n")
            if (s.logs.isNotEmpty()) append("\n部署日志（最近）：\n" + s.logs.takeLast(15).joinToString("\n") + "\n")
            append("\n说明：CMS 引擎是整套终端运行引擎（区别于能力模块），提供 NODE/PYTHON/SSH/JAVA/RUST/GO 等共享运行时；引擎就绪后，依赖这些运行时的能力模块才能运行。")
        }.trimEnd()
    }
}
