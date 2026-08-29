package com.ai.assistance.quro.core.cms

import android.content.Context
import com.ai.assistance.quro.core.policy.QuroPolicy
import com.ai.assistance.quro.core.policy.QuroPolicyStore
import com.ai.assistance.quro.core.tools.QuroTool
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

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

--- AI 自写脚本部署 ---
11. create_module: 创建自定义 CMS 模块
   - module_id: 模块 ID（如 "my.httpd"）
   - entry_script: entry.sh 脚本内容
   - cms_package: cms-package.json 内容（可选，自动生成）
   - backend_script: 后端脚本内容（可选，如 backend.js）
12. create_engine_script: 创建自定义引擎脚本
   - script_type: 脚本类型（"bootstrap" 或 "provisioner"）
   - script_content: 脚本内容
13. create_devenv_script: 创建自定义开发环境脚本
   - env_name: 环境名称（如 "myenv"）
   - install_script: 安装脚本内容
14. deploy_custom: 部署自定义脚本
   - type: 部署类型（"module", "engine", "devenv"）
   - id: 模块ID/环境名称
   - script_content: 脚本内容（create后部署时可省略）
15. list_scripts: 列出自定义脚本（无参数）

示例：
- cms_toolbox(action="list_modules")
- cms_toolbox(action="call_module", capability_id="echo_text", args={"text":"hello"})
- cms_toolbox(action="deploy_engine")
- cms_toolbox(action="repair_deployment", component="engine")
- cms_toolbox(action="create_module", module_id="my.httpd", entry_script="#!/bin/sh\\npython3 -m http.server 8080")
- cms_toolbox(action="deploy_custom", type="module", id="my.httpd")
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
            "lines":{"type":"integer","description":"日志行数（get_logs 时可选，默认 50）"},
            "entry_script":{"type":"string","description":"entry.sh 脚本内容（create_module 时需要）"},
            "cms_package":{"type":"string","description":"cms-package.json 内容（create_module 时可选）"},
            "backend_script":{"type":"string","description":"后端脚本内容（create_module 时可选）"},
            "script_type":{"type":"string","description":"脚本类型（create_engine_script 时需要）"},
            "script_content":{"type":"string","description":"脚本内容（create_engine_script/create_devenv_script 时需要）"},
            "env_name":{"type":"string","description":"环境名称（create_devenv_script 时需要）"},
            "install_script":{"type":"string","description":"安装脚本内容（create_devenv_script 时需要）"},
            "type":{"type":"string","description":"部署类型（deploy_custom 时需要）"},
            "id":{"type":"string","description":"模块ID/环境名称（deploy_custom 时需要）"}
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
            "run_script" -> handleRunScript(context, obj)
            "fix_deploy" -> handleFixDeploy(context)
            "get_install_scripts" -> handleGetInstallScripts(context, obj)
            "fix_modules" -> handleFixModules(context, obj)
            "save_fix_scripts" -> handleSaveFixScripts(context, obj)
            "save_install_scripts" -> handleSaveInstallScripts(context, obj)
            // AI 自写脚本部署
            "create_module" -> handleCreateModule(context, obj)
            "create_engine_script" -> handleCreateEngineScript(context, obj)
            "create_devenv_script" -> handleCreateDevenvScript(context, obj)
            "deploy_custom" -> handleDeployCustom(context, obj)
            "list_scripts" -> handleListScripts(context)
            else -> "不支持的 action: $action\n\n可用 action:\n- 模块管理: list_modules, call_module\n- 引擎管理: deploy_engine, status_engine\n- 开发环境: deploy_devenv, status_devenv\n- 修复部署: repair_deployment, fix_deploy\n- 配置管理: copy_config\n- 脚本操作: run_script, get_install_scripts, fix_modules\n- 保存到文件: save_fix_scripts, save_install_scripts\n- AI自写部署: create_module, create_engine_script, create_devenv_script, deploy_custom, list_scripts\n- 日志状态: get_logs, get_status"
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

        return if (result.ok) {
            "✅ CMS 能力执行成功\n能力: $capId\n结果: ${result.stdout}"
        } else {
            "❌ CMS 能力执行失败\n能力: $capId\n错误: ${result.stdout}"
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
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            appendLine("- 最后部署: ${if (snapshot.lastDeployAt > 0) dateFormat.format(Date(snapshot.lastDeployAt)) else "从未部署"}")
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
            val results = mutableListOf<String>()
            
            profiles.forEach { profileName ->
                val profile = EnvProfile.parse(profileName)
                if (profile != null) {
                    val result = CmsEnvProvisioner.provisionWithLog(context, profile) { line ->
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
            val profiles = listOf(EnvProfile.NODE, EnvProfile.PYTHON, EnvProfile.JAVA, EnvProfile.RUST, EnvProfile.GO, EnvProfile.SSH)
            buildString {
                appendLine("开发环境状态：")
                profiles.forEach { profile ->
                    val installed = CmsEnvProvisioner.isReady(context, profile)
                    appendLine("- ${profile.name}: ${if (installed) "✅ 已安装" else "❌ 未安装"}")
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
            // 1. 执行CMS引擎部署修复脚本（修复apt锁、dpkg异常等）
            val fixScript = """#!/bin/bash
# CMS v2 引擎部署修复脚本
echo "🔧 Step 1: 清除 apt 残留锁..."
rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/cache/apt/archives/lock /var/lib/apt/lists/lock
echo "✅ 锁文件已清除"

echo "🔧 Step 2: 创建缺失的缓存目录..."
mkdir -p /data/user/0/com.ai.assistance.quro/cache
echo "✅ 缓存目录已创建"

echo "🔧 Step 3: 修复 dpkg 半安装状态..."
export DEBIAN_FRONTEND=noninteractive
dpkg --configure -a 2>&1 || true
echo "✅ dpkg 状态已修复"

echo "🔧 Step 4: 手动修复 ca-certificates..."
update-ca-certificates 2>&1 || true
echo "✅ CA 证书已更新"

echo "🔧 Step 5: 验证修复结果..."
dpkg -l | grep -E "^(iF|iU)" | wc -l

echo ""
echo "🔧 Step 6: 验证运行时..."
echo "  Python: $(python3 --version 2>&1 || echo '未安装')"
echo "  Node:   $(node --version 2>&1 || echo '未安装')"
echo "  Java:   $(java -version 2>&1 | head -1 || echo '未安装')"
echo ""
echo "🎉 修复完成！"
""".trimIndent()

            // 通过终端执行修复脚本
            val fixResult = com.ai.assistance.quro.core.terminal.QuroTerminalController.runCommand(fixScript, 60_000L, context)
            android.util.Log.i("CmsToolbox", "修复脚本执行结果:\n${fixResult.output}")

            // 2. 清理可能损坏的引擎目录
            val engineDir = CmsEngineDeployer.engineHostDir(context)
            if (engineDir.exists()) {
                engineDir.deleteRecursively()
            }

            // 3. 重新部署引擎
            val enginePackage = CmsEnginePackage.builtin()
            val result = CmsEngineDeployer.deployEngine(context, enginePackage)
            "✅ CMS 引擎修复成功\n\n=== 修复脚本执行结果 ===\n${fixResult.output}\n\n=== 引擎部署结果 ===\n引擎 ID: ${enginePackage.engineId}\n部署结果: $result"
        } catch (e: Exception) {
            "❌ CMS 引擎修复失败: ${e.message}\n\n可尝试手动执行修复脚本:\nscripts/cms-fix-deploy.sh"
        }
    }

    private fun repairDevenv(context: Context): String {
        return try {
            // 重新部署基础环境
            val profiles = listOf(EnvProfile.NODE, EnvProfile.PYTHON, EnvProfile.JAVA)
            val results = mutableListOf<String>()
            
            profiles.forEach { profile ->
                val result = CmsEnvProvisioner.provisionWithLog(context, profile) { line ->
                    android.util.Log.i("CmsToolbox", line)
                }
                results.add("${profile.name}: $result")
            }
            
            "✅ 开发环境修复成功\n环境: node, python, java\n修复结果:\n${results.joinToString("\n")}"
        } catch (e: Exception) {
            "❌ 开发环境修复失败: ${e.message}\n\n可能需要手动检查 proot 环境或重装应用"
        }
    }

    private fun repairModule(context: Context, moduleId: String): String {
        return try {
            // 1. 卸载可能损坏的模块
            CmsTerminalDeployer.undeploy(context, moduleId)

            // 2. 重新部署模块
            val repo = QuroCmsRepository(context)
            val module = repo.get(moduleId) ?: return "未找到模块: $moduleId"
            val deployPackage = CmsDeployPackage.fromModule(module)
            val result = CmsTerminalDeployer.deploy(context, deployPackage)
            "✅ CMS 模块修复成功\n模块 ID: $moduleId\n修复结果: $result"
        } catch (e: Exception) {
            "❌ CMS 模块修复失败: ${e.message}\n\n可使用 cms_toolbox(action=\"list_modules\") 查看可用模块"
        }
    }

    private fun handleRunScript(context: Context, obj: JSONObject): String {
        val script = obj.optString("script", "").trim()
        val scriptName = obj.optString("script_name", "").trim()

        if (script.isEmpty() && scriptName.isEmpty()) {
            return "缺少 script 或 script_name 参数。\n\n可用脚本:\n- cms-fix-deploy: CMS引擎部署修复脚本\n- custom: 自定义脚本（需提供script参数）"
        }

        return try {
            val scriptToRun = when {
                scriptName == "cms-fix-deploy" -> {
                    // 内置的CMS修复脚本
                    """#!/bin/bash
# CMS v2 引擎部署修复脚本
echo "🔧 Step 1: 清除 apt 残留锁..."
rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/cache/apt/archives/lock /var/lib/apt/lists/lock
echo "✅ 锁文件已清除"

echo "🔧 Step 2: 创建缺失的缓存目录..."
mkdir -p /data/user/0/com.ai.assistance.quro/cache
echo "✅ 缓存目录已创建"

echo "🔧 Step 3: 修复 dpkg 半安装状态..."
export DEBIAN_FRONTEND=noninteractive
dpkg --configure -a 2>&1 || true
echo "✅ dpkg 状态已修复"

echo "🔧 Step 4: 手动修复 ca-certificates..."
update-ca-certificates 2>&1 || true
echo "✅ CA 证书已更新"

echo "🔧 Step 5: 验证修复结果..."
dpkg -l | grep -E "^(iF|iU)" | wc -l

echo ""
echo "🔧 Step 6: 验证运行时..."
echo "  Python: $(python3 --version 2>&1 || echo '未安装')"
echo "  Node:   $(node --version 2>&1 || echo '未安装')"
echo "  Java:   $(java -version 2>&1 | head -1 || echo '未安装')"
echo ""
echo "🎉 修复完成！"
""".trimIndent()
                }
                script.isNotEmpty() -> script
                else -> return "未找到脚本: $scriptName"
            }

            val result = com.ai.assistance.quro.core.terminal.QuroTerminalController.runCommand(scriptToRun, 60_000L, context)
            "✅ 脚本执行完成\n\n=== 执行结果 ===\n${result.output}"
        } catch (e: Exception) {
            "❌ 脚本执行失败: ${e.message}"
        }
    }

    private fun handleFixDeploy(context: Context): String {
        return try {
            // 1. 执行修复脚本
            val fixScript = """#!/bin/bash
echo "🔧 开始CMS引擎部署修复..."
echo ""

echo "🔧 Step 1: 清除 apt 残留锁..."
rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/cache/apt/archives/lock /var/lib/apt/lists/lock
echo "✅ 锁文件已清除"

echo "🔧 Step 2: 创建缺失的缓存目录..."
mkdir -p /data/user/0/com.ai.assistance.quro/cache
echo "✅ 缓存目录已创建"

echo "🔧 Step 3: 修复 dpkg 半安装状态..."
export DEBIAN_FRONTEND=noninteractive
dpkg --configure -a 2>&1 || true
echo "✅ dpkg 状态已修复"

echo "🔧 Step 4: 手动修复 ca-certificates..."
update-ca-certificates 2>&1 || true
echo "✅ CA 证书已更新"

echo "🔧 Step 5: 验证修复结果..."
dpkg -l | grep -E "^(iF|iU)" | wc -l

echo ""
echo "🔧 Step 6: 验证运行时..."
echo "  Python: $(python3 --version 2>&1 || echo '未安装')"
echo "  Node:   $(node --version 2>&1 || echo '未安装')"
echo "  Java:   $(java -version 2>&1 | head -1 || echo '未安装')"
echo ""
echo "🎉 修复完成！"
""".trimIndent()

            val fixResult = com.ai.assistance.quro.core.terminal.QuroTerminalController.runCommand(fixScript, 60_000L, context)
            android.util.Log.i("CmsToolbox", "修复脚本执行结果:\n${fixResult.output}")

            // 2. 清理可能损坏的引擎目录
            val engineDir = CmsEngineDeployer.engineHostDir(context)
            if (engineDir.exists()) {
                engineDir.deleteRecursively()
            }

            // 3. 重新部署引擎
            val enginePackage = CmsEnginePackage.builtin()
            val deployResult = CmsEngineDeployer.deployEngine(context, enginePackage)

            "✅ CMS引擎部署修复完成\n\n=== 修复脚本执行结果 ===\n${fixResult.output}\n\n=== 引擎部署结果 ===\n引擎 ID: ${enginePackage.engineId}\n部署结果: $deployResult"
        } catch (e: Exception) {
            "❌ CMS引擎部署修复失败: ${e.message}\n\n可尝试手动执行:\ncms_toolbox(action=\"run_script\", script_name=\"cms-fix-deploy\")"
        }
    }

    private fun handleCopyConfig(context: Context, obj: JSONObject): String {
        val target = obj.optString("target", "all").trim()
        val component = obj.optString("component", "").trim()
        
        // 如果指定了 component，复制特定组件的配置文件
        if (component.isNotEmpty()) {
            return when (component) {
                "engine" -> copyEngineConfig(context)
                "module" -> {
                    val moduleId = obj.optString("module_id", "").trim()
                    if (moduleId.isEmpty()) return "复制模块配置需要 module_id 参数"
                    copyModuleConfig(context, moduleId)
                }
                "devenv" -> copyDevenvConfig(context)
                else -> "不支持的 component: $component\n可用值: engine, module, devenv"
            }
        }
        
        // 否则按 target 复制
        return when (target) {
            "engine" -> copyEngineConfig(context)
            "devenv" -> copyDevenvConfig(context)
            "all" -> {
                val results = mutableListOf<String>()
                results.add(copyEngineConfig(context))
                results.add(copyDevenvConfig(context))
                results.joinToString("\n\n")
            }
            else -> "不支持的 target: $target\n可用值: engine, devenv, all"
        }
    }
    
    private fun copyEngineConfig(context: Context): String {
        return try {
            val enginePackage = CmsEnginePackage.builtin()
            val json = CmsEngineDeployer.exportPackage(enginePackage)
            
            // 保存到 Downloads 目录
            val fileName = "cms_engine_config.json"
            val result = com.ai.assistance.quro.core.tools.QuroDownloadUtil.saveTextToDownloads(
                context, fileName, "application/json", json
            )
            
            if (result.startsWith("OK:")) {
                "✅ CMS 引擎配置已保存到 Download/Quro/$fileName\n\n配置内容:\n```json\n$json\n```"
            } else {
                "⚠️ 配置保存失败，但配置内容如下:\n```json\n$json\n```"
            }
        } catch (e: Exception) {
            "❌ 获取引擎配置失败: ${e.message}"
        }
    }
    
    private fun copyDevenvConfig(context: Context): String {
        return try {
            val profiles = listOf(EnvProfile.NODE, EnvProfile.PYTHON, EnvProfile.JAVA, EnvProfile.RUST, EnvProfile.GO, EnvProfile.SSH)
            val config = buildString {
                appendLine("{")
                appendLine("  \"profiles\": {")
                profiles.forEachIndexed { index, profile ->
                    val installed = CmsEnvProvisioner.isReady(context, profile)
                    appendLine("    \"${profile.name.lowercase()}\": {")
                    appendLine("      \"name\": \"${profile.profileName}\",")
                    appendLine("      \"installed\": $installed")
                    if (index < profiles.lastIndex) appendLine("    },") else appendLine("    }")
                }
                appendLine("  },")
                appendLine("  \"terminal\": {")
                val terminalStatus = QuroLinuxEnv.probe(context)
                appendLine("    \"available\": ${terminalStatus.available}")
                appendLine("  }")
                appendLine("}")
            }
            
            val fileName = "cms_devenv_config.json"
            val result = com.ai.assistance.quro.core.tools.QuroDownloadUtil.saveTextToDownloads(
                context, fileName, "application/json", config
            )
            
            if (result.startsWith("OK:")) {
                "✅ 开发环境配置已保存到 Download/Quro/$fileName\n\n配置内容:\n```json\n$config\n```"
            } else {
                "⚠️ 配置保存失败，但配置内容如下:\n```json\n$config\n```"
            }
        } catch (e: Exception) {
            "❌ 获取开发环境配置失败: ${e.message}"
        }
    }
    
    private fun copyModuleConfig(context: Context, moduleId: String): String {
        return try {
            val repo = QuroCmsRepository(context)
            val module = repo.get(moduleId) ?: return "未找到模块: $moduleId"
            
            val config = buildString {
                appendLine("{")
                appendLine("  \"id\": \"${module.id}\",")
                appendLine("  \"name\": \"${module.name}\",")
                appendLine("  \"version\": \"${module.version}\",")
                appendLine("  \"description\": \"${module.description}\",")
                appendLine("  \"permissions\": [")
                module.permissions.forEachIndexed { index, perm ->
                    if (index < module.permissions.lastIndex) {
                        appendLine("    \"$perm\",")
                    } else {
                        appendLine("    \"$perm\"")
                    }
                }
                appendLine("  ]")
                appendLine("}")
            }
            
            val fileName = "cms_module_${moduleId}_config.json"
            val result = com.ai.assistance.quro.core.tools.QuroDownloadUtil.saveTextToDownloads(
                context, fileName, "application/json", config
            )
            
            if (result.startsWith("OK:")) {
                "✅ 模块配置已保存到 Download/Quro/$fileName\n\n配置内容:\n```json\n$config\n```"
            } else {
                "⚠️ 配置保存失败，但配置内容如下:\n```json\n$config\n```"
            }
        } catch (e: Exception) {
            "❌ 获取模块配置失败: ${e.message}"
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
            CmsEngineStore.init(context)
            val engineSnapshot = CmsEngineStore.snapshot.value
            val engineState = when {
                engineSnapshot.deploying -> "部署中"
                engineSnapshot.ready -> "就绪"
                else -> "未安装"
            }
            status.add("引擎: $engineState (版本: ${engineSnapshot.engineVersion.ifEmpty { "未知" }})")
            
            // 开发环境状态
            val profiles = listOf(EnvProfile.NODE, EnvProfile.PYTHON, EnvProfile.JAVA)
            val installedEnvs = profiles.filter { CmsEnvProvisioner.isReady(context, it) }.map { it.name }
            status.add("开发环境: ${if (installedEnvs.isEmpty()) "未安装" else "已安装: ${installedEnvs.joinToString(", ")}"}")
            
            // 模块状态
            val repo = QuroCmsRepository(context)
            val caps = repo.loadCapabilities()
            status.add("模块: ${caps.size} 个已安装")
            
            // 策略状态
            val policy = QuroPolicyStore.getCms(context)
            status.add("权限模式: ${policy.name}")
            
            // 终端状态
            val terminalStatus = QuroLinuxEnv.probe(context)
            status.add("终端环境: ${if (terminalStatus.available) "✅ 就绪" else "❌ 未就绪"}")
            
            status.joinToString("\n")
        } catch (e: Exception) {
            "❌ 获取状态失败: ${e.message}"
        }
    }

    private fun handleGetInstallScripts(context: Context, obj: JSONObject): String {
        val profiles = mutableListOf<String>()
        val profilesArray = obj.optJSONArray("profiles")
        if (profilesArray != null) {
            for (i in 0 until profilesArray.length()) {
                profiles.add(profilesArray.optString(i))
            }
        }
        
        if (profiles.isEmpty()) {
            // 默认返回所有开发环境的安装脚本
            profiles.addAll(listOf("node", "python", "java", "rust", "go"))
        }
        
        val sb = StringBuilder("开发环境安装脚本（可复制）：\n\n")
        
        profiles.forEach { profileName ->
            val profile = EnvProfile.parse(profileName)
            if (profile != null) {
                sb.appendLine("## ${profile.profileName}")
                sb.appendLine("```bash")
                sb.appendLine(profile.installScript.trimIndent())
                sb.appendLine("```\n")
            } else {
                sb.appendLine("## $profileName")
                sb.appendLine("❌ 未知环境配置\n")
            }
        }
        
        sb.appendLine("提示：以上脚本在 proot Ubuntu 环境中执行，用于安装对应的开发环境。")
        sb.appendLine("使用方式：在终端中粘贴执行，或通过 cms_toolbox(action=\"run_script\", script=\"...\") 执行。")

        return sb.toString().trim()
    }

    /**
     * 返回 CMS 模块修复脚本（DNS修复、httpd修复、node修复）。
     * 用户可以复制这些脚本到终端执行，或者通过 run_script action 执行。
     */
    private fun handleFixModules(context: Context, obj: JSONObject): String {
        val moduleType = obj.optString("module_type", "all").trim()

        val sb = StringBuilder("CMS 模块修复脚本（可复制到终端执行）：\n\n")

        // 1. bootstrap.sh DNS修复说明
        if (moduleType == "all" || moduleType == "dns") {
            sb.appendLine("## 1. bootstrap.sh DNS 部分修复")
            sb.appendLine("修复 bootstrap.sh 中有问题的 DNS heredoc 块（`|| {` 语法错误）：")
            sb.appendLine("")
            sb.appendLine("**修复方法**：删除 `|| {` 块，改为简单的 `<< 'DNS'` heredoc，只保留 5 个 nameserver。")
            sb.appendLine("")
            sb.appendLine("**修复后**：")
            sb.appendLine("```bash")
            sb.appendLine("cat > /etc/resolv.conf 2>/dev/null << 'DNS'")
            sb.appendLine("nameserver 8.8.8.8")
            sb.appendLine("nameserver 8.8.4.4")
            sb.appendLine("nameserver 223.5.5.5")
            sb.appendLine("nameserver 1.1.1.1")
            sb.appendLine("nameserver 9.9.9.9")
            sb.appendLine("DNS")
            sb.appendLine("```\n")
        }

        // 2-4: 从 assets 读取脚本文件
        val assetFiles = mapOf(
            "httpd" to Triple("2. httpd entry.sh", "cms/modules/quro.term.httpd/entry.sh", "bash"),
            "node_backend" to Triple("3. node backend.js", "cms/modules/quro.term.node/backend.js", "javascript"),
            "node_entry" to Triple("4. node entry.sh", "cms/modules/quro.term.node/entry.sh", "bash"),
            "fix_script" to Triple("5. 一键修复脚本", "cms/modules/cms-fix-modules.sh", "bash"),
        )

        val showHttpd = moduleType == "all" || moduleType == "httpd"
        val showNode = moduleType == "all" || moduleType == "node"
        val showFix = moduleType == "all" || moduleType == "fix"

        val toShow = mutableListOf<String>()
        if (showHttpd) toShow.addAll(listOf("httpd"))
        if (showNode) toShow.addAll(listOf("node_backend", "node_entry"))
        if (showFix) toShow.add("fix_script")

        toShow.forEach { key ->
            val (title, assetPath, lang) = assetFiles[key] ?: return@forEach
            sb.appendLine("## $title")
            sb.appendLine("```$lang")
            try {
                val content = context.assets.open(assetPath).bufferedReader().use { it.readText() }
                sb.appendLine(content.trimIndent())
            } catch (e: Exception) {
                sb.appendLine("(脚本文件缺失: $assetPath)")
            }
            sb.appendLine("```\n")
        }

        sb.appendLine("提示：")
        sb.appendLine("1. 以上脚本在 proot Ubuntu 环境中执行")
        sb.appendLine("2. 使用方式：在终端中粘贴执行，或通过 cms_toolbox(action=\"run_script\", script=\"...\") 执行")
        sb.appendLine("3. 一键修复脚本会自动修复 DNS、httpd 和 node 模块")

        return sb.toString().trim()
    }

    /**
     * 将 CMS 模块修复脚本保存到手机 Download/Quro/cms_fix/ 目录。
     * 用户可通过文件管理器直接访问、复制、执行。
     */
    private fun handleSaveFixScripts(context: Context, obj: JSONObject): String {
        val sb = StringBuilder()
        val savedFiles = mutableListOf<String>()

        // 保存一键修复脚本
        try {
            val fixContent = context.assets.open("cms/modules/cms-fix-modules.sh").bufferedReader().use { it.readText() }
            val result = com.ai.assistance.quro.core.tools.QuroDownloadUtil.saveTextToDownloads(
                context, "Quro/cms_fix/cms-fix-modules.sh", "text/x-shellscript", fixContent
            )
            if (result.startsWith("OK:")) {
                savedFiles.add("cms-fix-modules.sh")
                sb.appendLine("✅ cms-fix-modules.sh 已保存")
            } else {
                sb.appendLine("⚠️ cms-fix-modules.sh 保存失败: $result")
            }
        } catch (e: Exception) {
            sb.appendLine("❌ cms-fix-modules.sh: ${e.message}")
        }

        // 保存 httpd entry.sh
        try {
            val httpdContent = context.assets.open("cms/modules/quro.term.httpd/entry.sh").bufferedReader().use { it.readText() }
            val result = com.ai.assistance.quro.core.tools.QuroDownloadUtil.saveTextToDownloads(
                context, "Quro/cms_fix/quro.term.httpd_entry.sh", "text/x-shellscript", httpdContent
            )
            if (result.startsWith("OK:")) savedFiles.add("quro.term.httpd_entry.sh")
        } catch (e: Exception) { /* skip */ }

        // 保存 node backend.js
        try {
            val nodeJsContent = context.assets.open("cms/modules/quro.term.node/backend.js").bufferedReader().use { it.readText() }
            val result = com.ai.assistance.quro.core.tools.QuroDownloadUtil.saveTextToDownloads(
                context, "Quro/cms_fix/quro.term.node_backend.js", "application/javascript", nodeJsContent
            )
            if (result.startsWith("OK:")) savedFiles.add("quro.term.node_backend.js")
        } catch (e: Exception) { /* skip */ }

        // 保存 node entry.sh
        try {
            val nodeEntryContent = context.assets.open("cms/modules/quro.term.node/entry.sh").bufferedReader().use { it.readText() }
            val result = com.ai.assistance.quro.core.tools.QuroDownloadUtil.saveTextToDownloads(
                context, "Quro/cms_fix/quro.term.node_entry.sh", "text/x-shellscript", nodeEntryContent
            )
            if (result.startsWith("OK:")) savedFiles.add("quro.term.node_entry.sh")
        } catch (e: Exception) { /* skip */ }

        // 保存 httpd cms-package.json
        try {
            val httpdPkg = context.assets.open("cms/modules/quro.term.httpd/cms-package.json").bufferedReader().use { it.readText() }
            val result = com.ai.assistance.quro.core.tools.QuroDownloadUtil.saveTextToDownloads(
                context, "Quro/cms_fix/quro.term.httpd_cms-package.json", "application/json", httpdPkg
            )
            if (result.startsWith("OK:")) savedFiles.add("quro.term.httpd_cms-package.json")
        } catch (e: Exception) { /* skip */ }

        // 保存 node cms-package.json
        try {
            val nodePkg = context.assets.open("cms/modules/quro.term.node/cms-package.json").bufferedReader().use { it.readText() }
            val result = com.ai.assistance.quro.core.tools.QuroDownloadUtil.saveTextToDownloads(
                context, "Quro/cms_fix/quro.term.node_cms-package.json", "application/json", nodePkg
            )
            if (result.startsWith("OK:")) savedFiles.add("quro.term.node_cms-package.json")
        } catch (e: Exception) { /* skip */ }

        sb.appendLine("")
        sb.appendLine("📁 已保存 ${savedFiles.size} 个文件到 Download/Quro/cms_fix/：")
        savedFiles.forEach { sb.appendLine("  - $it") }
        sb.appendLine("")
        sb.appendLine("使用方式：")
        sb.appendLine("1. 在终端中: sh /sdcard/Download/Quro/cms_fix/cms-fix-modules.sh")
        sb.appendLine("2. 或在 AI 中: cms_toolbox(action=\"run_script\", script_name=\"cms-fix-modules\")")

        return sb.toString().trim()
    }

    /**
     * 将开发环境安装脚本保存到手机 Download/Quro/install_scripts/ 目录。
     * 用户可通过文件管理器直接访问、复制、执行。
     */
    private fun handleSaveInstallScripts(context: Context, obj: JSONObject): String {
        val profilesArray = obj.optJSONArray("profiles")
        val profiles = mutableListOf<String>()
        if (profilesArray != null) {
            for (i in 0 until profilesArray.length()) {
                profiles.add(profilesArray.optString(i))
            }
        }
        if (profiles.isEmpty()) {
            profiles.addAll(listOf("node", "python", "java", "rust", "go"))
        }

        val sb = StringBuilder()
        val savedFiles = mutableListOf<String>()

        profiles.forEach { profileName ->
            val profile = EnvProfile.parse(profileName)
            if (profile != null) {
                val fileName = "install_${profile.name.lowercase()}.sh"
                val content = "#!/bin/sh\n# ${profile.profileName} 安装脚本\n# 在 proot Ubuntu 环境中执行\n\nset -e\n\n${profile.installScript.trimIndent()}\n"
                val result = com.ai.assistance.quro.core.tools.QuroDownloadUtil.saveTextToDownloads(
                    context, "Quro/install_scripts/$fileName", "text/x-shellscript", content
                )
                if (result.startsWith("OK:")) {
                    savedFiles.add(fileName)
                    sb.appendLine("✅ ${profile.profileName} → $fileName")
                } else {
                    sb.appendLine("⚠️ ${profile.profileName}: $result")
                }
            } else {
                sb.appendLine("❌ $profileName: 未知环境配置")
            }
        }

        sb.appendLine("")
        sb.appendLine("📁 已保存 ${savedFiles.size} 个安装脚本到 Download/Quro/install_scripts/：")
        savedFiles.forEach { sb.appendLine("  - $it") }
        sb.appendLine("")
        sb.appendLine("使用方式：在终端中 sh /sdcard/Download/Quro/install_scripts/install_xxx.sh")

        return sb.toString().trim()
    }

    // ═══════════════ AI 自写脚本部署 ═══════════════

    /**
     * 创建自定义 CMS 模块
     */
    private fun handleCreateModule(context: Context, obj: JSONObject): String {
        val moduleId = obj.optString("module_id", "").trim()
        if (moduleId.isEmpty()) return "缺少 module_id 参数。"

        val entryScript = obj.optString("entry_script", "").trim()
        if (entryScript.isEmpty()) return "缺少 entry_script 参数。"

        val cmsPackageJson = obj.optString("cms_package", "").trim()
        val backendScript = obj.optString("backend_script", "").trim()

        return try {
            // 1. 创建模块目录
            val moduleDir = File(context.filesDir, "cms_modules/$moduleId")
            moduleDir.mkdirs()

            // 2. 写入 entry.sh
            val entryFile = File(moduleDir, "entry.sh")
            entryFile.writeText(entryScript)

            // 3. 生成或使用提供的 cms-package.json
            val cmsPackage = if (cmsPackageJson.isNotEmpty()) {
                cmsPackageJson
            } else {
                // 自动生成
                JSONObject().apply {
                    put("moduleId", moduleId)
                    put("name", moduleId)
                    put("version", "1.0.0")
                    put("entry", "entry.sh")
                    put("entryContent", entryScript)
                    put("apkDeps", "")
                    put("pipDeps", "")
                    put("envProfiles", "")
                    put("env", JSONObject())
                    put("ports", "")
                }.toString(2)
            }
            val cmsPackageFile = File(moduleDir, "cms-package.json")
            cmsPackageFile.writeText(cmsPackage)

            // 4. 如果提供了后端脚本，写入
            if (backendScript.isNotEmpty()) {
                val backendFile = File(moduleDir, "backend.js")
                backendFile.writeText(backendScript)
            }

            // 5. 注册到 CMS 仓库
            val repo = QuroCmsRepository(context)
            val module = QuroCmsModule(
                id = moduleId,
                name = moduleId,
                version = "1.0.0",
                description = "自定义模块: $moduleId",
                terminalEntry = entryScript,
                capabilities = listOf(
                    QuroCmsCapability(
                        id = "${moduleId}_run",
                        summary = "运行 $moduleId",
                        schema = "{}",
                        requiresPermissions = emptyList(),
                        constraints = PermissionConstraints(),
                        actionType = "terminal",
                        action = "sh ${moduleDir.absolutePath}/entry.sh"
                    )
                )
            )
            repo.upsert(module)

            "✅ 自定义 CMS 模块创建成功\n" +
                "模块 ID: $moduleId\n" +
                "目录: ${moduleDir.absolutePath}\n" +
                "文件: entry.sh, cms-package.json${if (backendScript.isNotEmpty()) ", backend.js" else ""}\n\n" +
                "使用方式:\n" +
                "1. 部署: cms_toolbox(action=\"deploy_custom\", type=\"module\", id=\"$moduleId\")\n" +
                "2. 调用: cms_toolbox(action=\"call_module\", capability_id=\"${moduleId}_run\")"
        } catch (e: Exception) {
            "❌ 创建模块失败: ${e.message}"
        }
    }

    /**
     * 创建自定义引擎脚本
     */
    private fun handleCreateEngineScript(context: Context, obj: JSONObject): String {
        val scriptType = obj.optString("script_type", "").trim()
        if (scriptType.isEmpty() || scriptType !in listOf("bootstrap", "provisioner")) {
            return "缺少或无效的 script_type 参数。可用值: bootstrap, provisioner"
        }

        val scriptContent = obj.optString("script_content", "").trim()
        if (scriptContent.isEmpty()) return "缺少 script_content 参数。"

        return try {
            val scriptsDir = File(context.filesDir, "cms_scripts")
            scriptsDir.mkdirs()

            val scriptFile = File(scriptsDir, "engine_${scriptType}.sh")
            scriptFile.writeText(scriptContent)

            "✅ 自定义引擎脚本创建成功\n" +
                "类型: $scriptType\n" +
                "文件: ${scriptFile.absolutePath}\n\n" +
                "使用方式:\n" +
                "1. 部署: cms_toolbox(action=\"deploy_custom\", type=\"engine\", id=\"$scriptType\")\n" +
                "2. 执行: cms_toolbox(action=\"run_script\", script=\"${scriptContent.take(50)}...\")"
        } catch (e: Exception) {
            "❌ 创建引擎脚本失败: ${e.message}"
        }
    }

    /**
     * 创建自定义开发环境脚本
     */
    private fun handleCreateDevenvScript(context: Context, obj: JSONObject): String {
        val envName = obj.optString("env_name", "").trim()
        if (envName.isEmpty()) return "缺少 env_name 参数。"

        val installScript = obj.optString("install_script", "").trim()
        if (installScript.isEmpty()) return "缺少 install_script 参数。"

        return try {
            val scriptsDir = File(context.filesDir, "cms_scripts/devenv")
            scriptsDir.mkdirs()

            val scriptFile = File(scriptsDir, "install_${envName}.sh")
            scriptFile.writeText(installScript)

            "✅ 自定义开发环境脚本创建成功\n" +
                "环境名称: $envName\n" +
                "文件: ${scriptFile.absolutePath}\n\n" +
                "使用方式:\n" +
                "1. 部署: cms_toolbox(action=\"deploy_custom\", type=\"devenv\", id=\"$envName\")\n" +
                "2. 执行: cms_toolbox(action=\"run_script\", script=\"${installScript.take(50)}...\")"
        } catch (e: Exception) {
            "❌ 创建开发环境脚本失败: ${e.message}"
        }
    }

    /**
     * 部署自定义脚本
     */
    private fun handleDeployCustom(context: Context, obj: JSONObject): String {
        val type = obj.optString("type", "").trim()
        if (type.isEmpty() || type !in listOf("module", "engine", "devenv")) {
            return "缺少或无效的 type 参数。可用值: module, engine, devenv"
        }

        val id = obj.optString("id", "").trim()
        if (id.isEmpty()) return "缺少 id 参数。"

        val scriptContent = obj.optString("script_content", "").trim()

        return try {
            when (type) {
                "module" -> deployCustomModule(context, id, scriptContent)
                "engine" -> deployCustomEngine(context, id, scriptContent)
                "devenv" -> deployCustomDevenv(context, id, scriptContent)
                else -> "不支持的部署类型: $type"
            }
        } catch (e: Exception) {
            "❌ 部署失败: ${e.message}"
        }
    }

    private fun deployCustomModule(context: Context, moduleId: String, scriptContent: String): String {
        val moduleDir = File(context.filesDir, "cms_modules/$moduleId")
        if (!moduleDir.exists()) {
            return "模块目录不存在: $moduleId\n请先使用 create_module 创建模块"
        }

        // 如果提供了新的脚本内容，更新
        if (scriptContent.isNotEmpty()) {
            val entryFile = File(moduleDir, "entry.sh")
            entryFile.writeText(scriptContent)
        }

        // 部署到终端
        val repo = QuroCmsRepository(context)
        val module = repo.get(moduleId) ?: return "未找到模块: $moduleId"
        val deployPackage = CmsDeployPackage.fromModule(module)
        val result = CmsTerminalDeployer.deploy(context, deployPackage)

        return "✅ 自定义模块部署成功\n" +
            "模块 ID: $moduleId\n" +
            "部署结果: $result"
    }

    private fun deployCustomEngine(context: Context, engineType: String, scriptContent: String): String {
        val scriptsDir = File(context.filesDir, "cms_scripts")
        val scriptFile = File(scriptsDir, "engine_${engineType}.sh")

        if (!scriptFile.exists() && scriptContent.isEmpty()) {
            return "引擎脚本不存在: $engineType\n请先使用 create_engine_script 创建脚本"
        }

        // 如果提供了新的脚本内容，更新
        if (scriptContent.isNotEmpty()) {
            scriptFile.parentFile?.mkdirs()
            scriptFile.writeText(scriptContent)
        }

        // 执行脚本
        val scriptToRun = scriptFile.readText()
        val result = com.ai.assistance.quro.core.terminal.QuroTerminalController.runCommand(
            scriptToRun, 60_000L, context
        )

        return "✅ 自定义引擎脚本部署成功\n" +
            "类型: $engineType\n" +
            "执行结果: ${result.output}"
    }

    private fun deployCustomDevenv(context: Context, envName: String, scriptContent: String): String {
        val scriptsDir = File(context.filesDir, "cms_scripts/devenv")
        val scriptFile = File(scriptsDir, "install_${envName}.sh")

        if (!scriptFile.exists() && scriptContent.isEmpty()) {
            return "开发环境脚本不存在: $envName\n请先使用 create_devenv_script 创建脚本"
        }

        // 如果提供了新的脚本内容，更新
        if (scriptContent.isNotEmpty()) {
            scriptFile.parentFile?.mkdirs()
            scriptFile.writeText(scriptContent)
        }

        // 执行脚本
        val scriptToRun = scriptFile.readText()
        val result = com.ai.assistance.quro.core.terminal.QuroTerminalController.runCommand(
            scriptToRun, 120_000L, context
        )

        return "✅ 自定义开发环境部署成功\n" +
            "环境名称: $envName\n" +
            "执行结果: ${result.output}"
    }

    /**
     * 列出自定义脚本
     */
    private fun handleListScripts(context: Context): String {
        val sb = StringBuilder("自定义脚本列表：\n\n")

        // 检查自定义模块
        val modulesDir = File(context.filesDir, "cms_modules")
        if (modulesDir.exists()) {
            val customModules = modulesDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            if (customModules.isNotEmpty()) {
                sb.appendLine("📁 自定义 CMS 模块:")
                customModules.forEach { dir ->
                    val moduleId = dir.name
                    val entryFile = File(dir, "entry.sh")
                    val hasEntry = entryFile.exists()
                    sb.appendLine("  - $moduleId ${if (hasEntry) "✅" else "❌"}")
                }
                sb.appendLine()
            }
        }

        // 检查自定义引擎脚本
        val scriptsDir = File(context.filesDir, "cms_scripts")
        if (scriptsDir.exists()) {
            val engineScripts = scriptsDir.listFiles { file -> file.name.startsWith("engine_") } ?: emptyArray()
            if (engineScripts.isNotEmpty()) {
                sb.appendLine("🔧 自定义引擎脚本:")
                engineScripts.forEach { file ->
                    val type = file.name.removePrefix("engine_").removeSuffix(".sh")
                    sb.appendLine("  - $type (${file.length()} bytes)")
                }
                sb.appendLine()
            }

            // 检查自定义开发环境脚本
            val devenvDir = File(scriptsDir, "devenv")
            if (devenvDir.exists()) {
                val devenvScripts = devenvDir.listFiles() ?: emptyArray()
                if (devenvScripts.isNotEmpty()) {
                    sb.appendLine("💻 自定义开发环境脚本:")
                    devenvScripts.forEach { file ->
                        val envName = file.name.removePrefix("install_").removeSuffix(".sh")
                        sb.appendLine("  - $envName (${file.length()} bytes)")
                    }
                    sb.appendLine()
                }
            }
        }

        if (sb.toString() == "自定义脚本列表：\n\n") {
            sb.appendLine("暂无自定义脚本。")
            sb.appendLine("\n使用以下命令创建：")
            sb.appendLine("- cms_toolbox(action=\"create_module\", module_id=\"my.httpd\", entry_script=\"...\")")
            sb.appendLine("- cms_toolbox(action=\"create_engine_script\", script_type=\"bootstrap\", script_content=\"...\")")
            sb.appendLine("- cms_toolbox(action=\"create_devenv_script\", env_name=\"myenv\", install_script=\"...\")")
        }

        return sb.toString().trim()
    }
}