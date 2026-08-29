package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
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
开发环境管理工具：安装/卸载/检查终端开发环境。

参数格式：{"action":"动作", "env":"环境名"}

支持的 action：
1. install: 安装指定环境
   - env: 环境名称（node/python/java/rust/go/git/all）
2. uninstall: 卸载指定环境
   - env: 环境名称
3. check: 检查环境状态
   - env: 环境名称（可选，不填则检查所有）
4. list: 列出所有支持的环境

支持的环境：
- node: Node.js 20.x + npm
- python: Python3 + pip + venv
- java: OpenJDK 17
- rust: Rust + Cargo
- go: Go 编程语言
- git: Git + Curl + Wget
- all: 全部安装

示例：
- dev_env(action="install", env="node")
- dev_env(action="uninstall", env="python")
- dev_env(action="check")
- dev_env(action="list")
""".trimIndent()

    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"动作类型: install/uninstall/check/list"},
            "env":{"type":"string","description":"环境名称: node/python/java/rust/go/git/all"}
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
source $HOME/.cargo/env
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
rm -rf $HOME/.cargo $HOME/.rustup
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

        return when (action) {
            "install" -> handleInstall(env)
            "uninstall" -> handleUninstall(env)
            "check" -> handleCheck(env)
            "list" -> handleList()
            else -> "不支持的 action: $action\n\n可用 action: install, uninstall, check, list"
        }
    }

    private fun handleInstall(env: String): String {
        if (env.isEmpty()) return "请指定要安装的环境: env=node/python/java/rust/go/git/all"
        
        if (env == "all") {
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

    private fun handleUninstall(env: String): String {
        if (env.isEmpty()) return "请指定要卸载的环境: env=node/python/java/rust/go/git"
        
        val cmd = uninstallCommands[env]
            ?: return "不支持的环境: $env\n\n支持的环境: ${uninstallCommands.keys.joinToString(", ")}"

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

    private fun handleCheck(env: String): String {
        if (env.isEmpty()) {
            // 检查所有环境
            return buildString {
                appendLine("=== 环境状态检查 ===")
                appendLine()
                appendLine("请在终端中执行以下命令：")
                appendLine()
                appendLine("echo '=== 开发环境检查 ==='")
                checkCommands.forEach { (name, cmd) ->
                    appendLine()
                    appendLine(cmd)
                }
                appendLine()
                appendLine("命令发送到终端后，输出将显示在终端界面。")
            }
        }

        val cmd = checkCommands[env]
            ?: return "不支持的环境: $env\n\n支持的环境: ${checkCommands.keys.joinToString(", ")}"

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
        }
    }
}
