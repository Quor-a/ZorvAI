package com.ai.assistance.quro.core.cms

import android.content.Context
import com.ai.assistance.quro.core.policy.QuroPolicy
import com.ai.assistance.quro.core.policy.QuroPolicyStore
import com.ai.assistance.quro.core.tools.QuroTool
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

// 导入CMS相关类
import com.ai.assistance.quro.core.cms.CmsStateStore
import com.ai.assistance.quro.core.cms.CmsEngineStore
import com.ai.assistance.quro.core.cms.CmsEnvProvisioner
import com.ai.assistance.quro.core.cms.CmsTerminalDeployer
import com.ai.assistance.quro.core.cms.CmsEnginePackage
import com.ai.assistance.quro.core.cms.CmsEngineDeployer
import com.ai.assistance.quro.core.cms.QuroCmsRepository
import com.ai.assistance.quro.core.cms.InvocationTarget
import com.ai.assistance.quro.core.cms.CmsHostRouter
import com.ai.assistance.quro.core.cms.CmsExecResult
import com.ai.assistance.quro.core.cms.QuroCmsExecutor
import com.ai.assistance.quro.core.linux.QuroLinuxEnv

/**
 * CMS v2 统一工具箱（原创）：整合 CMS 模块管理、引擎管理、开发环境管理、部署修复于一体。
 * 
 * 设计目标：
 * 1. 单一入口：AI 通过 cms_toolbox(action, ...) 调用所有 CMS 功能
 * 2. 部署失败时 AI 可接手修复：自动诊断并尝试修复
 * 3. 支持复制正确的引擎/模块/开发环境配置
 * 4. 统一状态查询和日志查看
 * 
 * 支持的 action：
 * - list_modules: 列出已安装的 CMS 模块
 * - call_module: 调用 CMS 模块能力
 * - deploy_engine: 部署 CMS 引擎
 * - status_engine: 查看 CMS 引擎状态
 * - deploy_devenv: 部署开发环境
 * - status_devenv: 查看开发环境状态
 * - repair_deployment: 修复部署失败
 * - copy_config: 复制正确的配置
 * - get_logs: 获取 CMS 日志
 * - get_status: 获取 CMS 总体状态
 */
class QuroCmsToolboxTool : QuroTool {
    override val name = "cms_toolbox"
    override val description = """
CMS v2 统一工具箱：整合 CMS 模块管理、引擎管理、开发环境管理、部署修复于一体。
当用户需要管理 CMS 模块、引擎、开发环境或修复部署问题时使用此工具。

参数格式：{"action":"动作", "参数名":"参数值"}

支持的 action：
1. list_modules: 列出已安装的 CMS 模块（无参数）
2. call_module: 调用 CMS 模块能力
   - capability_id: 能力 ID
   - args: 参数对象（可选）
   - target: 运行宿主（可选，默认 auto）
3. deploy_engine: 部署 CMS 引擎
   - engine_id: 引擎 ID（可选，默认使用内置引擎）
4. status_engine: 查看 CMS 引擎状态（无参数）
5. deploy_devenv: 部署开发环境
   - profiles: 环境配置列表（如 ["node", "python", "java"]）
6. status_devenv: 查看开发环境状态（无参数）
7. repair_deployment: 修复部署失败
   - component: 要修复的组件（"engine", "devenv", "module"）
   - module_id: 模块 ID（当 component="module" 时需要）
8. copy_config: 复制正确的配置
   - target: 复制目标（"engine", "devenv", "all"）
9. get_logs: 获取 CMS 日志
   - component: 组件名称（可选，不填则获取所有）
   - lines: 行数（可选，默认 50）
10. get_status: 获取 CMS 总体状态（无参数）

示例：
- cms_toolbox(action="list_modules")
- cms_toolbox(action="call_module", capability_id="echo_text", args={"text":"hello"})
- cms_toolbox(action="deploy_engine")
- cms_toolbox(action="repair_deployment", component="engine")
""".trimIndent()

    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"动作类型"},
            "capability_id":{"type":"string","description":"能力 ID（call_module 时需要）"},
            "args":{"type":"object","description":"参数对象（call_module 时可选）"},
            "target":{"type":"string","description":"运行宿主（call_module 时可选，默认 auto）"},
            "engine_id":{"type":"string","description":"引擎 ID（deploy_engine 时可选）"},
            "profiles":{"type":"array","items":{"type":"string"},"description":"环境配置列表（deploy_devenv 时需要）"},
            "component":{"type":"string","description":"组件名称（repair_deployment 时需要）"},
            "module_id":{"type":"string","description":"模块 ID（repair_deployment component=module 时需要）"},
            "lines":{"type":"integer","description":"日志行数（get_logs 时可选，默认 50）"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val obj = runCatching { JSONObject(arguments) }.getOrElse { 
            return "参数不是合法 JSON：$arguments" 
        }
        val action = obj.optString("action", "").trim()
        if (action.isEmpty()) return "缺少 action 参数。"

        return when (action) {
            "list_modules" -> handleListModules(context)
            "call_module" -> handleCallModule(context, obj)
            "deploy_engine" -> handleDeployEngine(context, obj)
            "status_engine" -> handleStatusEngine(context)
            "deploy_devenv" -> handleDeployDevenv(context, obj)
            "status_devenv" -> handleStatusDevenv(context)
            "repair_deployment" -> handleRepairDeployment(context, obj)
            "copy_config" -> handleCopyConfig(context, obj)
            "get_logs" -> handleGetLogs(context, obj)
            "get_status" -> handleGetStatus(context)
            else -> "不支持的 action: $action\n\n可用 action: list_modules, call_module, deploy_engine, status_engine, deploy_devenv, status_devenv, repair_deployment, copy_config, get_logs, get_status"
        }
    }

    private fun handleListModules(context: Context): String {
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
        sb.append("\n当前 CMS v2 权限模式：${policy.name}（允许/禁止/询问）")
        return sb.toString().trim()
    }

    private fun handleCallModule(context: Context, obj: JSONObject): String {
        val capId = obj.optString("capability_id", "").trim()
        if (capId.isEmpty()) return "缺少 capability_id 参数。"

        val repo = QuroCmsRepository(context)
        val pair = repo.loadCapabilities().firstOrNull { it.second.id == capId }
            ?: return "未找到能力：$capId（先用 cms_toolbox(action=\"list_modules\") 查看可用能力）。"
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
                    CmsExecResult(false, 1, "⚠️ CMS v2 权限模式=询问：该能力需要授权，已被策略拦截。" +
                            "请在对话底部控制条切到「允许」模式后重试。", "", emptyList(), 0, taskId)
                } else {
                    CmsStateStore.finishTask(taskId, true, "ok")
                    CmsExecResult(true, 0, res, "", emptyList(), 0, taskId)
                }
            }
            QuroPolicy.ALLOW -> runBlocking {
                val res = QuroCmsExecutor(context).execute(
                    module = module, cap = cap, args = argsMap, uiRequest = { null }, host = host,
                )
                CmsStateStore.finishTask(taskId, true, "ok")
                CmsExecResult(true, 0, res, "", emptyList(), 0, taskId)
            }
        }

        return if (result.success) {
            "✅ CMS 能力执行成功\n能力: $capId\n结果: ${result.output}"
        } else {
            "❌ CMS 能力执行失败\n能力: $capId\n错误: ${result.output}"
        }
    }

    private fun handleDeployEngine(context: Context, obj: JSONObject): String {
        val engineId = obj.optString("engine_id", "").trim()
        
        return try {
            // 使用内置引擎（engineId参数暂时保留，未来可支持自定义引擎）
            val enginePackage = CmsEnginePackage.builtin()
            
            val result = CmsEngineDeployer.deployEngine(context, enginePackage)
            "✅ CMS 引擎部署成功\n引擎 ID: ${enginePackage.engineId}\n部署结果: $result"
        } catch (e: Exception) {
            "❌ CMS 引擎部署失败: ${e.message}\n\n可使用 cms_toolbox(action=\"repair_deployment\", component=\"engine\") 尝试修复"
        }
    }

    private fun handleStatusEngine(context: Context): String {
        CmsEngineStore.init(context)
        val snapshot = CmsEngineStore.snapshot.value
        return buildString {
            appendLine("CMS 引擎状态：")
            appendLine("- 就绪: ${if (snapshot.ready) "✅ 是" else "❌ 否"}")
            appendLine("- 健康: ${if (snapshot.health) "✅ 是" else "❌ 否"}")
            appendLine("- 引擎版本: ${snapshot.engineVersion.ifEmpty { "未安装" }}")
            appendLine("- 服务: ${snapshot.services.joinToString(", ").ifEmpty { "无" }}")
            appendLine("- 部署中: ${if (snapshot.deploying) "✅ 是" else "❌ 否"}")
            if (snapshot.deploying) {
                appendLine("- 部署步骤: ${snapshot.deployStep}")
            }
            appendLine("- 最后部署: ${if (snapshot.lastDeployAt > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(snapshot.lastDeployAt)) else "从未部署"}")
            if (snapshot.lastError.isNotEmpty()) {
                appendLine("- 错误: ${snapshot.lastError}")
            }
            appendLine("\n可使用 cms_toolbox(action=\"deploy_engine\") 部署引擎")
        }
    }

    private fun handleDeployDevenv(context: Context, obj: JSONObject): String {
        val profiles = mutableListOf<String>()
        val profilesArray = obj.optJSONArray("profiles")
        if (profilesArray != null) {
            for (i in 0 until profilesArray.length()) {
                profiles.add(profilesArray.optString(i))
            }
        }
        
        if (profiles.isEmpty()) {
            return "缺少 profiles 参数。可用环境: node, python, java, rust, go, ssh"
        }
        
        return try {
            val provisioner = CmsEnvProvisioner(context)
            val results = mutableListOf<String>()
            
            profiles.forEach { profileName ->
                val profile = CmsEnvProvisioner.EnvProfile.parse(profileName)
                if (profile != null) {
                    val result = provisioner.provisionWithLog(context, profile) { line ->
                        // 日志输出到控制台
                        android.util.Log.i("CmsToolbox", line)
                    }
                    results.add("$profileName: $result")
                } else {
                    results.add("$profileName: ❌ 未知环境配置")
                }
            }
            
            "✅ 开发环境部署完成\n环境: ${profiles.joinToString(", ")}\n结果:\n${results.joinToString("\n")}"
        } catch (e: Exception) {
            "❌ 开发环境部署失败: ${e.message}\n\n可使用 cms_toolbox(action=\"repair_deployment\", component=\"devenv\") 尝试修复"
        }
    }

    private fun handleStatusDevenv(context: Context): String {
        return try {
            val provisioner = CmsEnvProvisioner(context)
            val profiles = listOf("NODE", "PYTHON", "JAVA", "RUST", "GO", "SSH")
            buildString {
                appendLine("开发环境状态：")
                profiles.forEach { profileName ->
                    val profile = CmsEnvProvisioner.EnvProfile.valueOf(profileName)
                    val installed = provisioner.isReady(context, profile)
                    appendLine("- $profileName: ${if (installed) "✅ 已安装" else "❌ 未安装"}")
                }
                appendLine("\n可使用 cms_toolbox(action=\"deploy_devenv\", profiles=[\"node\",\"python\"]) 安装环境")
            }
        } catch (e: Exception) {
            "❌ 获取开发环境状态失败: ${e.message}"
        }
    }

    private fun handleRepairDeployment(context: Context, obj: JSONObject): String {
        val component = obj.optString("component", "").trim()
        if (component.isEmpty()) return "缺少 component 参数。可用值: engine, devenv, module"
        
        val moduleId = obj.optString("module_id", "").trim()
        
        return when (component) {
            "engine" -> repairEngine(context)
            "devenv" -> repairDevenv(context)
            "module" -> {
                if (moduleId.isEmpty()) return "repair_deployment component=module 时需要 module_id 参数"
                repairModule(context, moduleId)
            }
            else -> "不支持的 component: $component\n可用值: engine, devenv, module"
        }
    }

    private fun repairEngine(context: Context): String {
        return try {
            // 1. 清理可能损坏的引擎目录
            val engineDir = CmsEngineDeployer.engineHostDir(context)
            if (engineDir.exists()) {
                engineDir.deleteRecursively()
            }
            
            // 2. 重新部署引擎
            val enginePackage = CmsEnginePackage.builtin()
            val result = CmsEngineDeployer.deployEngine(context, enginePackage)
            "✅ CMS 引擎修复成功\n引擎 ID: ${enginePackage.engineId}\n修复结果: $result"
        } catch (e: Exception) {
            "❌ CMS 引擎修复失败: ${e.message}\n\n可能需要手动检查 proot 环境或重装应用"
        }
    }

    private fun repairDevenv(context: Context): String {
        return try {
            // 重新部署基础环境
            val provisioner = CmsEnvProvisioner(context)
            val profiles = listOf("NODE", "PYTHON", "JAVA")
            val results = mutableListOf<String>()
            
            profiles.forEach { profileName ->
                val profile = CmsEnvProvisioner.EnvProfile.valueOf(profileName)
                val result = provisioner.provisionWithLog(context, profile) { line ->
                    android.util.Log.i("CmsToolbox", line)
                }
                results.add("$profileName: $result")
            }
            
            "✅ 开发环境修复成功\n环境: node, python, java\n修复结果:\n${results.joinToString("\n")}"
        } catch (e: Exception) {
            "❌ 开发环境修复失败: ${e.message}\n\n可能需要手动检查 proot 环境或重装应用"
        }
    }

    private fun repairModule(context: Context, moduleId: String): String {
        return try {
            // 1. 卸载可能损坏的模块
            val deployer = CmsTerminalDeployer(context)
            deployer.undeploy(context, moduleId)
            
            // 2. 重新部署模块
            val repo = QuroCmsRepository(context)
            val module = repo.get(moduleId) ?: return "未找到模块: $moduleId"
            val deployPackage = CmsDeployPackage.fromModule(module)
            val result = deployer.deploy(context, deployPackage)
            "✅ CMS 模块修复成功\n模块 ID: $moduleId\n修复结果: $result"
        } catch (e: Exception) {
            "❌ CMS 模块修复失败: ${e.message}\n\n可使用 cms_toolbox(action=\"list_modules\") 查看可用模块"
        }
    }

    private fun handleCopyConfig(context: Context, obj: JSONObject): String {
        val target = obj.optString("target", "all").trim()
        
        return when (target) {
            "engine" -> {
                val enginePackage = CmsEnginePackage.builtin()
                val json = CmsEngineDeployer.exportPackage(enginePackage)
                "✅ CMS 引擎配置已复制\n配置内容:\n$json"
            }
            "devenv" -> {
                // 开发环境配置：列出可用的环境配置
                val profiles = CmsEnvProvisioner.EnvProfile.entries
                val config = buildString {
                    appendLine("开发环境配置：")
                    profiles.forEach { profile ->
                        appendLine("- ${profile.name}: ${profile.description}")
                    }
                    appendLine("\n可用环境: ${profiles.joinToString(", ") { it.name.lowercase() }}")
                }
                "✅ 开发环境配置已复制\n配置内容:\n$config"
            }
            "all" -> {
                val enginePackage = CmsEnginePackage.builtin()
                val engineJson = CmsEngineDeployer.exportPackage(enginePackage)
                val profiles = CmsEnvProvisioner.EnvProfile.entries
                val devenvConfig = buildString {
                    appendLine("开发环境配置：")
                    profiles.forEach { profile ->
                        appendLine("- ${profile.name}: ${profile.description}")
                    }
                }
                "✅ 所有配置已复制\n\n=== CMS 引擎配置 ===\n$engineJson\n\n=== 开发环境配置 ===\n$devenvConfig"
            }
            else -> "不支持的 target: $target\n可用值: engine, devenv, all"
        }
    }

    private fun handleGetLogs(context: Context, obj: JSONObject): String {
        val component = obj.optString("component", "").trim()
        val lines = obj.optInt("lines", 50)
        
        return try {
            val logs = mutableListOf<String>()
            
            if (component.isEmpty() || component == "engine") {
                CmsEngineStore.init(context)
                val engineSnapshot = CmsEngineStore.snapshot.value
                val engineLogs = engineSnapshot.logs.takeLast(lines)
                if (engineLogs.isNotEmpty()) {
                    logs.add("=== CMS 引擎日志 ===")
                    logs.addAll(engineLogs)
                }
            }
            
            if (component.isEmpty() || component == "state") {
                // CmsStateStore.getLogs需要moduleId，这里获取摘要
                val stateSummary = CmsStateStore.summary()
                if (stateSummary.isNotEmpty()) {
                    logs.add("=== CMS 状态摘要 ===")
                    logs.add(stateSummary)
                }
            }
            
            if (logs.isEmpty()) {
                "没有找到相关日志"
            } else {
                logs.joinToString("\n")
            }
        } catch (e: Exception) {
            "❌ 获取日志失败: ${e.message}"
        }
    }

    private fun handleGetStatus(context: Context): String {
        return try {
            val status = mutableListOf<String>()
            status.add("=== CMS v2 总体状态 ===")
            
            // 引擎状态
            val engineStore = CmsEngineStore(context)
            val engineStatus = engineStore.getStatus()
            status.add("引擎: ${engineStatus.state} (ID: ${engineStatus.engineId ?: "未安装"})")
            
            // 开发环境状态
            val provisioner = CmsEnvProvisioner(context)
            val devenvStatus = provisioner.getStatus()
            val installedEnvs = devenvStatus.filter { it.value }.keys.joinToString(", ")
            status.add("开发环境: ${if (installedEnvs.isEmpty()) "未安装" else "已安装: $installedEnvs"}")
            
            // 模块状态
            val repo = QuroCmsRepository(context)
            val caps = repo.loadCapabilities()
            status.add("模块: ${caps.size} 个已安装")
            
            // 策略状态
            val policy = QuroPolicyStore.getCms(context)
            status.add("权限模式: ${policy.name}")
            
            // 终端状态
            val terminalReady = QuroLinuxEnv.isProotReady(context)
            status.add("终端环境: ${if (terminalReady) "✅ 就绪" else "❌ 未就绪"}")
            
            status.joinToString("\n")
        } catch (e: Exception) {
            "❌ 获取状态失败: ${e.message}"
        }
    }
}