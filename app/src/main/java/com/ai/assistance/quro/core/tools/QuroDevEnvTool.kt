package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.linux.SourceManager
import com.ai.assistance.quro.core.linux.PackageManagerType
import com.ai.assistance.quro.core.linux.MirrorSource
import com.ai.assistance.quro.core.terminal.QuroTerminalController
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 开发环境管理工具：独立管理终端开发环境的安装/卸载/检查。
 * 
 * 设计目标：
 * 1. AI 可以直接管理终端开发环境（Node.js、Python、Java、Rust、Go、Git）
 * 2. 每个环境有标准的安装/卸载/检查命令
 * 3. 命令通过终端会话执行，输出可见
 */
class QuroDevEnvTool : QuroTool {
    override val name = "dev_env"
    override val description = """
开发环境管理工具：安装/卸载/检查终端开发环境，管理镜像源。

参数格式：{"action":"动作", "env":"环境名", "execute":true/false, "source":"源ID", "pm":"包管理器"}

支持的 action：
1. install: 安装指定环境（自动配置镜像源）
   - env: 环境名称（node/python/java/rust/go/git/all）
   - execute: 是否直接在终端执行（默认false）
2. uninstall: 卸载指定环境
   - env: 环境名称
   - execute: 是否直接在终端执行（默认false）
3. check: 检查环境状态
   - env: 环境名称（可选，不填则检查所有）
   - execute: 是否直接在终端执行（默认false）
4. list: 列出所有支持的环境
5. source_list: 列出所有可用的镜像源
   - pm: 包管理器类型（apt/pip/npm/rust，可选，不填则列出所有）
6. source_select: 选择镜像源
   - pm: 包管理器类型（apt/pip/npm/rust）
   - source: 源ID
7. source_config: 生成当前镜像源配置命令
   - execute: 是否直接在终端执行（默认false）

支持的环境：
- node: Node.js 20.x + npm
- python: Python3 + pip + venv
- java: OpenJDK 17
- rust: Rust + Cargo
- go: Go 编程语言
- git: Git + Curl + Wget
- all: 全部安装

示例：
- dev_env(action="install", env="node")  # 只返回命令文本
- dev_env(action="install", env="node", execute=true)  # 直接执行命令（自动配置镜像源）
- dev_env(action="uninstall", env="python")
- dev_env(action="check")
- dev_env(action="list")
- dev_env(action="source_list", pm="apt")  # 列出 APT 镜像源
- dev_env(action="source_select", pm="apt", source="tuna_apt")  # 选择清华源
- dev_env(action="source_config", execute=true)  # 配置所有镜像源
""".trimIndent()

    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"动作类型: install/uninstall/check/list/source_list/source_select/source_config"},
            "env":{"type":"string","description":"环境名称: node/python/java/rust/go/git/all"},
            "execute":{"type":"boolean","description":"是否直接在终端执行命令（默认false，只返回命令文本）"},
            "pm":{"type":"string","description":"包管理器类型: apt/pip/npm/rust"},
            "source":{"type":"string","description":"镜像源ID"}
        },
        "required":["action"]
    }"""

    /** 环境安装命令 */
    private val installCommands = mapOf(
        "node" to """
# 安装 Node.js 20.x + npm
apt-get update
apt-get install -y nodejs npm
echo "Node.js 安装完成"
node -v && npm -v
""".trimIndent(),

        "python" to """
# 安装 Python3 + pip + venv
apt-get update
apt-get install -y python3 python3-pip python3-venv
echo "Python 安装完成"
python3 --version && pip3 --version
""".trimIndent(),

        "java" to """
# 安装 OpenJDK 17
apt-get update
apt-get install -y openjdk-17-jdk-headless
echo "Java 安装完成"
java -version
""".trimIndent(),

        "rust" to """
# 安装 Rust + Cargo
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
source ~/.cargo/env
echo "Rust 安装完成"
rustc --version && cargo --version
""".trimIndent(),

        "go" to """
# 安装 Go
apt-get update
apt-get install -y golang
echo "Go 安装完成"
go version
""".trimIndent(),

        "git" to """
# 安装 Git + Curl + Wget
apt-get update
apt-get install -y git curl wget
echo "Git/Curl/Wget 安装完成"
git --version && curl --version | head -1 && wget --version | head -1
""".trimIndent(),
    )

    /** 环境卸载命令 */
    private val uninstallCommands = mapOf(
        "node" to """
# 卸载 Node.js
apt-get remove -y nodejs npm 2>/dev/null
rm -rf /usr/local/lib/node_modules /usr/local/bin/npm /usr/local/bin/node
echo "Node.js 已卸载"
""".trimIndent(),

        "python" to """
# 卸载 Python
apt-get remove -y python3 python3-pip python3-venv 2>/dev/null
rm -rf /usr/local/bin/python* /usr/local/bin/pip*
echo "Python 已卸载"
""".trimIndent(),

        "java" to """
# 卸载 Java
apt-get remove -y openjdk-17-jdk-headless 2>/dev/null
echo "Java 已卸载"
""".trimIndent(),

        "rust" to """
# 卸载 Rust
rm -rf ~/.cargo ~/.rustup
echo "Rust 已卸载"
""".trimIndent(),

        "go" to """
# 卸载 Go
apt-get remove -y golang 2>/dev/null
rm -rf /usr/local/go
echo "Go 已卸载"
""".trimIndent(),

        "git" to """
# 卸载 Git/Curl/Wget
apt-get remove -y git curl wget 2>/dev/null
echo "Git/Curl/Wget 已卸载"
""".trimIndent(),
    )

    /** 环境检查命令 */
    private val checkCommands = mapOf(
        "node" to """echo -n "Node: " && node -v 2>/dev/null || echo "未安装"
echo -n "NPM: " && npm -v 2>/dev/null || echo "未安装\"""",
        "python" to """echo -n "Python: " && python3 --version 2>/dev/null || echo "未安装"
echo -n "Pip: " && pip3 --version 2>/dev/null || echo "未安装\"""",
        "java" to """echo -n "Java: " && java -version 2>&1 | head -1 || echo "未安装\"""",
        "rust" to """echo -n "Rust: " && rustc --version 2>/dev/null || echo "未安装"
echo -n "Cargo: " && cargo --version 2>/dev/null || echo "未安装\"""",
        "go" to """echo -n "Go: " && go version 2>/dev/null || echo "未安装\"""",
        "git" to """echo -n "Git: " && git --version 2>/dev/null || echo "未安装"
echo -n "Curl: " && curl --version 2>/dev/null | head -1 || echo "未安装"
echo -n "Wget: " && wget --version 2>/dev/null | head -1 || echo "未安装\"""",
    )

    override fun run(context: Context, arguments: String): String {
        val obj = JSONObject(arguments)
        val action = obj.optString("action", "list")
        val env = obj.optString("env", "")
        val execute = obj.optBoolean("execute", false)
        val pm = obj.optString("pm", "")
        val source = obj.optString("source", "")

        return when (action) {
            "install" -> handleInstall(env, execute, context)
            "uninstall" -> handleUninstall(env, execute, context)
            "check" -> handleCheck(env, execute, context)
            "list" -> handleList()
            "source_list" -> handleSourceList(pm, context)
            "source_select" -> handleSourceSelect(pm, source, context)
            "source_config" -> handleSourceConfig(execute, context)
            else -> "不支持的 action: $action\n\n可用 action: install, uninstall, check, list, source_list, source_select, source_config"
        }
    }

    private fun handleInstall(env: String, execute: Boolean, context: Context): String {
        if (env.isEmpty()) return "请指定要安装的环境: env=node/python/java/rust/go/git/all"

        if (env == "all") {
            val allCommands = installCommands.values.joinToString("\n")
            if (execute) {
                return executeCommand(allCommands, "安装所有开发环境", context)
            }
            return buildString {
                appendLine("=== 安装所有开发环境 ===")
                appendLine()
                appendLine("将依次安装以下环境：")
                appendLine("1. Node.js 20.x + npm")
                appendLine("2. Python3 + pip + venv")
                appendLine("3. OpenJDK 17")
                appendLine("4. Rust + Cargo")
                appendLine("5. Go")
                appendLine("6. Git + Curl + Wget")
                appendLine()
                appendLine("请在终端中依次执行以下命令：")
                appendLine()
                installCommands.forEach { (name, cmd) ->
                    appendLine("--- $name ---")
                    appendLine(cmd)
                    appendLine()
                }
            }
        }

        val cmd = installCommands[env]
            ?: return "不支持的环境: $env\n\n支持的环境: ${installCommands.keys.joinToString(", ")}"

        if (execute) {
            return executeCommand(cmd, "安装 $env", context)
        }

        return buildString {
            appendLine("=== 安装 $env ===")
            appendLine()
            appendLine("请在终端中执行以下命令：")
            appendLine()
            appendLine(cmd)
            appendLine()
            appendLine("命令发送到终端后，输出将显示在终端界面。")
        }
    }

    private fun handleUninstall(env: String, execute: Boolean, context: Context): String {
        if (env.isEmpty()) return "请指定要卸载的环境: env=node/python/java/rust/go/git"

        val cmd = uninstallCommands[env]
            ?: return "不支持的环境: $env\n\n支持的环境: ${uninstallCommands.keys.joinToString(", ")}"

        if (execute) {
            return executeCommand(cmd, "卸载 $env", context)
        }

        return buildString {
            appendLine("=== 卸载 $env ===")
            appendLine()
            appendLine("请在终端中执行以下命令：")
            appendLine()
            appendLine(cmd)
            appendLine()
            appendLine("命令发送到终端后，输出将显示在终端界面。")
        }
    }

    private fun handleCheck(env: String, execute: Boolean, context: Context): String {
        if (env.isEmpty()) {
            // 检查所有环境
            val allCommands = buildString {
                appendLine("echo '=== 开发环境检查 ==='")
                checkCommands.forEach { (name, cmd) ->
                    appendLine()
                    appendLine(cmd)
                }
            }
            if (execute) {
                return executeCommand(allCommands, "检查所有环境", context)
            }
            return buildString {
                appendLine("=== 环境状态检查 ===")
                appendLine()
                appendLine("请在终端中执行以下命令：")
                appendLine()
                appendLine(allCommands)
                appendLine()
                appendLine("命令发送到终端后，输出将显示在终端界面。")
            }
        }

        val cmd = checkCommands[env]
            ?: return "不支持的环境: $env\n\n支持的环境: ${checkCommands.keys.joinToString(", ")}"

        if (execute) {
            return executeCommand(cmd, "检查 $env 状态", context)
        }

        return buildString {
            appendLine("=== 检查 $env 状态 ===")
            appendLine()
            appendLine("请在终端中执行以下命令：")
            appendLine()
            appendLine(cmd)
            appendLine()
            appendLine("命令发送到终端后，输出将显示在终端界面。")
        }
    }

    private fun handleList(): String {
        return buildString {
            appendLine("=== 支持的开发环境 ===")
            appendLine()
            appendLine("| 环境 | 说明 | 安装命令 |")
            appendLine("|------|------|----------|")
            appendLine("| node | Node.js 20.x + npm | apt-get install nodejs npm |")
            appendLine("| python | Python3 + pip + venv | apt-get install python3 python3-pip python3-venv |")
            appendLine("| java | OpenJDK 17 | apt-get install openjdk-17-jdk-headless |")
            appendLine("| rust | Rust + Cargo | curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y |")
            appendLine("| go | Go 编程语言 | apt-get install golang |")
            appendLine("| git | Git + Curl + Wget | apt-get install git curl wget |")
            appendLine("| all | 全部安装 | 依次安装上述所有环境 |")
            appendLine()
            appendLine("使用示例：")
            appendLine("- dev_env(action=\"install\", env=\"node\")")
            appendLine("- dev_env(action=\"uninstall\", env=\"python\")")
            appendLine("- dev_env(action=\"check\")")
            appendLine("- dev_env(action=\"list\")")
            appendLine()
            appendLine("直接执行命令：")
            appendLine("- dev_env(action=\"install\", env=\"node\", execute=true)")
            appendLine("- dev_env(action=\"uninstall\", env=\"python\", execute=true)")
            appendLine("- dev_env(action=\"check\", env=\"node\", execute=true)")
        }
    }

    /**
     * 执行终端命令
     * @param command 要执行的命令
     * @param description 命令描述
     * @param context Android Context
     * @param includeSourceConfig 是否包含镜像源配置（默认true）
     * @return 执行结果
     */
    private fun executeCommand(command: String, description: String, context: Context, includeSourceConfig: Boolean = true): String {
        return try {
            // 确保终端会话存在
            QuroTerminalController.createSession(context)
            
            // 构建完整命令
            val fullCommand = if (includeSourceConfig) {
                val sourceManager = SourceManager(context)
                val sourceConfig = sourceManager.generateAllSourceConfigCommands()
                """
                # 配置镜像源
                $sourceConfig
                
                # 执行 $description 命令
                $command
                """.trimIndent()
            } else {
                command
            }
            
            // 发送命令到终端
            QuroTerminalController.sendToShell(fullCommand)
            buildString {
                appendLine("✅ $description 命令已发送到终端")
                if (includeSourceConfig) {
                    appendLine("已自动配置镜像源（当前选择：${SourceManager(context).getSelectedSourceId(com.ai.assistance.quro.core.linux.PackageManagerType.APT)}）")
                }
                appendLine()
                appendLine("命令将在终端中执行，输出会显示在终端界面。")
                appendLine()
                appendLine("请查看终端界面查看执行结果。")
            }
        } catch (e: Exception) {
            buildString {
                appendLine("❌ 执行命令失败: ${e.message}")
                appendLine()
                appendLine("请确保终端环境已正确初始化。")
                appendLine("可以尝试先运行: linux_install() 初始化Linux环境")
            }
        }
    }

    private fun handleSourceList(pm: String, context: Context): String {
        val sourceManager = SourceManager(context)
        val sources = when (pm.lowercase()) {
            "apt" -> sourceManager.aptSources
            "pip" -> sourceManager.pipSources
            "npm" -> sourceManager.npmSources
            "rust" -> sourceManager.rustSources
            else -> {
                // 列出所有源
                val allSources = mutableListOf<Pair<String, List<MirrorSource>>>()
                allSources.add("APT" to sourceManager.aptSources)
                allSources.add("Pip/Uv" to sourceManager.pipSources)
                allSources.add("NPM" to sourceManager.npmSources)
                allSources.add("Rust" to sourceManager.rustSources)
                
                return buildString {
                    appendLine("=== 所有镜像源 ===")
                    appendLine()
                    allSources.forEach { (pmName, sourcesList) ->
                        appendLine("### $pmName 镜像源")
                        appendLine("| ID | 名称 | URL | 中文源 |")
                        appendLine("|----|------|-----|--------|")
                        sourcesList.forEach { source ->
                            val selected = if (sourceManager.getSelectedSourceId(
                                    when (pmName) {
                                        "APT" -> PackageManagerType.APT
                                        "Pip/Uv" -> PackageManagerType.PIP
                                        "NPM" -> PackageManagerType.NPM
                                        "Rust" -> PackageManagerType.RUST
                                        else -> PackageManagerType.APT
                                    }
                                ) == source.id) "✓" else ""
                            appendLine("| ${source.id} | ${source.name} | ${source.url} | ${if (source.isChinese) "是" else "否"} $selected |")
                        }
                        appendLine()
                    }
                    appendLine("使用示例：")
                    appendLine("- dev_env(action=\"source_select\", pm=\"apt\", source=\"tuna_apt\")")
                }
            }
        }
        
        return buildString {
            appendLine("=== ${pm.uppercase()} 镜像源 ===")
            appendLine()
            appendLine("| ID | 名称 | URL | 中文源 | 当前选择 |")
            appendLine("|----|------|-----|--------|----------|")
            val selectedId = sourceManager.getSelectedSourceId(
                when (pm.lowercase()) {
                    "apt" -> PackageManagerType.APT
                    "pip" -> PackageManagerType.PIP
                    "npm" -> PackageManagerType.NPM
                    "rust" -> PackageManagerType.RUST
                    else -> PackageManagerType.APT
                }
            )
            sources.forEach { source ->
                val selected = if (source.id == selectedId) "✓" else ""
                appendLine("| ${source.id} | ${source.name} | ${source.url} | ${if (source.isChinese) "是" else "否"} | $selected |")
            }
            appendLine()
            appendLine("当前选择: $selectedId")
            appendLine()
            appendLine("使用示例：")
            appendLine("- dev_env(action=\"source_select\", pm=\"$pm\", source=\"tuna_apt\")")
        }
    }
    
    private fun handleSourceSelect(pm: String, source: String, context: Context): String {
        if (pm.isEmpty() || source.isEmpty()) {
            return "请指定包管理器类型和源ID：pm=apt/pip/npm/rust, source=源ID"
        }
        
        val sourceManager = SourceManager(context)
        val pmType = when (pm.lowercase()) {
            "apt" -> PackageManagerType.APT
            "pip" -> PackageManagerType.PIP
            "npm" -> PackageManagerType.NPM
            "rust" -> PackageManagerType.RUST
            else -> return "不支持的包管理器类型: $pm\n\n支持的类型: apt, pip, npm, rust"
        }
        
        // 验证源ID是否存在
        val sources = when (pmType) {
            PackageManagerType.APT -> sourceManager.aptSources
            PackageManagerType.PIP -> sourceManager.pipSources
            PackageManagerType.NPM -> sourceManager.npmSources
            PackageManagerType.RUST -> sourceManager.rustSources
        }
        
        val sourceObj = sources.find { it.id == source }
            ?: return "源ID '$source' 不存在\n\n可用源ID: ${sources.joinToString(", ") { it.id }}"
        
        // 保存选择
        sourceManager.setSelectedSourceId(pmType, source)
        
        return buildString {
            appendLine("✅ 已选择 ${pm.uppercase()} 镜像源: ${sourceObj.name}")
            appendLine()
            appendLine("源URL: ${sourceObj.url}")
            appendLine()
            appendLine("后续安装命令将自动使用此镜像源。")
            appendLine()
            appendLine("配置命令预览：")
            when (pmType) {
                PackageManagerType.APT -> appendLine(sourceManager.getAptSourceChangeCommand(sourceObj))
                PackageManagerType.PIP -> appendLine(sourceManager.getPipSourceChangeCommand(sourceObj))
                PackageManagerType.NPM -> appendLine(sourceManager.getNpmSourceChangeCommand(sourceObj))
                PackageManagerType.RUST -> appendLine(sourceManager.getRustSourceEnvCommand(sourceObj))
            }
        }
    }
    
    private fun handleSourceConfig(execute: Boolean, context: Context): String {
        val sourceManager = SourceManager(context)
        val configCommand = sourceManager.generateAllSourceConfigCommands()
        
        if (execute) {
            return executeCommand(configCommand, "配置镜像源", context, false) // 不重复配置
        }
        
        return buildString {
            appendLine("=== 当前镜像源配置 ===")
            appendLine()
            appendLine("APT 源: ${sourceManager.getSelectedSource(PackageManagerType.APT).name} (${sourceManager.getSelectedSourceId(PackageManagerType.APT)})")
            appendLine("Pip/Uv 源: ${sourceManager.getSelectedSource(PackageManagerType.PIP).name} (${sourceManager.getSelectedSourceId(PackageManagerType.PIP)})")
            appendLine("NPM 源: ${sourceManager.getSelectedSource(PackageManagerType.NPM).name} (${sourceManager.getSelectedSourceId(PackageManagerType.NPM)})")
            appendLine("Rust 源: ${sourceManager.getSelectedSource(PackageManagerType.RUST).name} (${sourceManager.getSelectedSourceId(PackageManagerType.RUST)})")
            appendLine()
            appendLine("配置命令：")
            appendLine(configCommand)
            appendLine()
            appendLine("直接执行配置：")
            appendLine("- dev_env(action=\"source_config\", execute=true)")
        }
    }
}
