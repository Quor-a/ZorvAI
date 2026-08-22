package com.ai.assistance.quro.core.cms

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv

/**
 * CMS v2 终端环境供给器（原创运行时 · 开发环境栈补齐）。
 *
 * 把"开发环境栈"作为可声明依赖（[DepKind.ENV]）落到 proot/Alpine aarch64 沙箱：
 * 模块在「添加模块」对话框勾选所需环境档（Node/Python/SSH/Java/Rust/Go），
 * 部署到终端时由本供给器按需装配。装配幂等（标记文件 + command -v 探活），
 * 且**非致命**：任一档失败仅记日志继续，避免单档网络问题阻断整包部署。
 *
 * 执行通道唯一 = proot（与 CmsTerminalDeployer 一致）；终端未就绪直接拒绝。
 *
 * 注意：installScript 内所有 shell `$` 必须以 `${'$'}` 转义——Kotlin 原始字符串
 * 仍会按模板解析裸 `$`，否则编译报 "Unresolved reference"。
 */
enum class EnvProfile(
    /** 人类可读档名（UI 展示）。 */
    val profileName: String,
    /** 就绪探测命令（sh -c，全部成立返回 0 才算就绪）。 */
    val checkCmd: String,
    /** 装配脚本（sh -c，best-effort，失败不阻断）。 */
    val installScript: String,
    /** 超时时间（毫秒），默认10分钟。 */
    val timeoutMs: Long = 600_000,
) {
    NODE(
        "Node.js 前端栈 (Node + PNPM + TypeScript)",
        "command -v node >/dev/null 2>&1 && command -v pnpm >/dev/null 2>&1 && command -v tsc >/dev/null 2>&1",
        """
        |apk add --no-cache nodejs npm 2>&1 | tail -2 || true
        |npm install -g pnpm typescript 2>&1 | tail -3 || true
        |echo "[env] node=${'$'}(node --version 2>&1) pnpm=${'$'}(pnpm --version 2>&1) tsc=${'$'}(tsc --version 2>&1)
        """.trimMargin(),
    ),
    PYTHON(
        "Python 栈 (python→python3 链接 / venv / pip / UV)",
        "command -v python >/dev/null 2>&1 && (command -v pip >/dev/null 2>&1 || command -v pip3 >/dev/null 2>&1) && command -v uv >/dev/null 2>&1",
        """
        |apk add --no-cache python3 py3-pip 2>&1 | tail -2 || true
        |if ! command -v python >/dev/null 2>&1; then ln -sf ${'$'}(command -v python3) /usr/local/bin/python; fi
        |python3 -m ensurepip --upgrade 2>/dev/null || true
        |if ! command -v uv >/dev/null 2>&1; then
        |  curl -LsSf https://astral.sh/uv/install.sh | sh 2>&1 | tail -3 || true
        |  ln -sf "${'$'}HOME/.local/bin/uv" /usr/local/bin/uv 2>/dev/null || true
        |fi
        |python3 -m venv /root/cms-venv 2>/dev/null || true
        |echo "[env] python=${'$'}(python --version 2>&1) uv=${'$'}(uv --version 2>&1)
        """.trimMargin(),
    ),
    SSH(
        "SSH 工具链 (openssh + sshpass + sshd 反向隧道)",
        "command -v ssh >/dev/null 2>&1 && command -v sshpass >/dev/null 2>&1 && command -v sshd >/dev/null 2>&1",
        """
        |apk add --no-cache openssh sshpass 2>&1 | tail -2 || true
        |if [ ! -f /etc/ssh/ssh_host_rsa_key ]; then ssh-keygen -A 2>/dev/null || true; fi
        |echo "[env] ssh=${'$'}(ssh -V 2>&1) sshpass=${'$'}(sshpass -V 2>&1 | head -1)
        """.trimMargin(),
    ),
    JAVA(
        "Java 栈 (OpenJDK 17 + Gradle)",
        "command -v java >/dev/null 2>&1 && command -v gradle >/dev/null 2>&1",
        """
        |apk add --no-cache openjdk17 2>&1 | tail -2 || true
        |ln -sf ${'$'}(command -v java) /usr/local/bin/java 2>/dev/null || true
        |if ! command -v gradle >/dev/null 2>&1; then apk add --no-cache gradle 2>&1 | tail -2 || true; fi
        |echo "[env] java=${'$'}(java -version 2>&1 | head -1)
        """.trimMargin(),
    ),
    RUST(
        "Rust / Cargo (rustup)",
        "command -v cargo >/dev/null 2>&1",
        """
        |echo "[rust] 开始安装 Rust..."
        |if ! command -v cargo >/dev/null 2>&1; then
        |  echo "[rust] 执行 rustup 安装（首次安装可能需要10-15分钟）..."
        |  export RUSTUP_HOME="${'$'}HOME/.rustup"
        |  export CARGO_HOME="${'$'}HOME/.cargo"
        |  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal 2>&1 || true
        |  export PATH="${'$'}CARGO_HOME/bin:${'$'}PATH"
        |  ln -sf "${'$'}CARGO_HOME/bin/cargo" /usr/local/bin/cargo 2>/dev/null || true
        |  ln -sf "${'$'}CARGO_HOME/bin/rustc" /usr/local/bin/rustc 2>/dev/null || true
        |else
        |  echo "[rust] cargo 已存在，跳过安装"
        |fi
        |echo "[rust] cargo=${'$'}(cargo --version 2>&1) rustc=${'$'}(rustc --version 2>&1)
        """.trimMargin(),
        timeoutMs = 1_200_000,  // 20分钟
    ),
    GO(
        "Go 语言栈 (Go)",
        "command -v go >/dev/null 2>&1",
        """
        |apk add --no-cache go 2>&1 | tail -2 || true
        |ln -sf ${'$'}(command -v go) /usr/local/bin/go 2>/dev/null || true
        |echo "[env] go=${'$'}(go version 2>&1)
        """.trimMargin(),
    ),
    // ─── Python 子环境 ───
    PYTHON_LINK(
        "Python 链接 (python→python3)",
        "command -v python >/dev/null 2>&1",
        """
        |if ! command -v python >/dev/null 2>&1; then
        |  ln -sf ${'$'}(command -v python3) /usr/local/bin/python 2>/dev/null || true
        |fi
        |echo "[env] python=${'$'}(python --version 2>&1)
        """.trimMargin(),
    ),
    PIP(
        "Pip (Python 包管理器)",
        "command -v pip >/dev/null 2>&1 || command -v pip3 >/dev/null 2>&1",
        """
        |apk add --no-cache py3-pip 2>&1 | tail -2 || true
        |python3 -m ensurepip --upgrade 2>/dev/null || true
        |echo "[env] pip=${'$'}(pip --version 2>&1 || pip3 --version 2>&1)
        """.trimMargin(),
    ),
    UV(
        "UV (Rust 极速 Python 包安装器)",
        "command -v uv >/dev/null 2>&1",
        """
        |if ! command -v uv >/dev/null 2>&1; then
        |  curl -LsSf https://astral.sh/uv/install.sh | sh 2>&1 | tail -3 || true
        |  ln -sf "${'$'}HOME/.local/bin/uv" /usr/local/bin/uv 2>/dev/null || true
        |fi
        |echo "[env] uv=${'$'}(uv --version 2>&1)
        """.trimMargin(),
    ),
    VENV(
        "Python 虚拟环境 (venv)",
        "test -x /root/cms-venv/bin/python3",
        """
        |python3 -m venv /root/cms-venv 2>/dev/null || true
        |echo "[env] venv=${'$'}(/root/cms-venv/bin/python3 --version 2>&1)"
        """.trimMargin(),
    ),
    // ─── Node.js 子环境 ───
    NODEJS(
        "Node.js (JavaScript 运行时)",
        "command -v node >/dev/null 2>&1",
        """
        |apk add --no-cache nodejs npm 2>&1 | tail -2 || true
        |echo "[env] node=${'$'}(node --version 2>&1)
        """.trimMargin(),
    ),
    PNPM(
        "PNPM (快速包管理器 + TypeScript)",
        "command -v pnpm >/dev/null 2>&1",
        """
        |if ! command -v pnpm >/dev/null 2>&1; then
        |  npm install -g pnpm 2>&1 | tail -3 || true
        |fi
        |if ! command -v tsc >/dev/null 2>&1; then
        |  npm install -g typescript 2>&1 | tail -3 || true
        |fi
        |echo "[env] pnpm=${'$'}(pnpm --version 2>&1) tsc=${'$'}(tsc --version 2>&1)
        """.trimMargin(),
    ),
    // ─── SSH 子环境 ───
    SSH_CLIENT(
        "SSH 客户端 (openssh)",
        "command -v ssh >/dev/null 2>&1",
        """
        |apk add --no-cache openssh-client 2>&1 | tail -2 || true
        |echo "[env] ssh=${'$'}(ssh -V 2>&1)
        """.trimMargin(),
    ),
    SSHPASS(
        "sshpass (密码认证工具)",
        "command -v sshpass >/dev/null 2>&1",
        """
        |apk add --no-cache sshpass 2>&1 | tail -2 || true
        |echo "[env] sshpass=${'$'}(sshpass -V 2>&1 | head -1)
        """.trimMargin(),
    ),
    SSH_SERVER(
        "OpenSSH 服务器 (反向隧道)",
        "command -v sshd >/dev/null 2>&1",
        """
        |apk add --no-cache openssh-server 2>&1 | tail -2 || true
        |if [ ! -f /etc/ssh/ssh_host_rsa_key ]; then ssh-keygen -A 2>/dev/null || true; fi
        |echo "[env] sshd=${'$'}(sshd -V 2>&1 | head -1)
        """.trimMargin(),
    ),
    // ─── Java 子环境 ───
    OPENJDK17(
        "OpenJDK 17 (Java 开发环境)",
        "command -v java >/dev/null 2>&1",
        """
        |apk add --no-cache openjdk17 2>&1 | tail -2 || true
        |ln -sf ${'$'}(command -v java) /usr/local/bin/java 2>/dev/null || true
        |echo "[env] java=${'$'}(java -version 2>&1 | head -1)
        """.trimMargin(),
    ),
    GRADLE(
        "Gradle (构建自动化工具)",
        "command -v gradle >/dev/null 2>&1",
        """
        |apk add --no-cache gradle 2>&1 | tail -2 || true
        |echo "[env] gradle=${'$'}(gradle --version 2>&1 | head -3)
        """.trimMargin(),
    );

    companion object {
        /** 按档名（不区分大小写）解析；无法识别返回 null。 */
        fun parse(name: String): EnvProfile? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

object CmsEnvProvisioner {

    private const val MARKER_DIR = "/root/.cms-env"

    /** 单个档是否已就绪（proot 内：标记文件优先，否则 command -v 探活）。 */
    fun isReady(context: Context, profile: EnvProfile): Boolean {
        val st = QuroLinuxEnv.probe(context)
        if (!st.available) {
            android.util.Log.w("CmsEnvProvisioner", "${profile.name}: 终端环境不可用")
            return false
        }
        // 检查标记文件
        val (mc, _) = QuroLinuxEnv.run(context, "[ -f $MARKER_DIR/${profile.name}.done ]", timeoutMs = 10_000)
        if (mc == 0) {
            android.util.Log.i("CmsEnvProvisioner", "${profile.name}: 标记文件存在，已就绪")
            return true
        }
        // 运行检查命令
        val (c, out) = QuroLinuxEnv.run(context, profile.checkCmd, timeoutMs = 20_000)
        android.util.Log.i("CmsEnvProvisioner", "${profile.name}: checkCmd exit=$c, output=${out.take(100)}")
        return c == 0
    }

    /** 供给单个档：已就绪/已标记则跳过；否则跑装配脚本（best-effort）。返回人类可读结果。 */
    fun provision(context: Context, profile: EnvProfile): String {
        val st = QuroLinuxEnv.probe(context)
        if (!st.available) return "⛔ 终端环境未就绪，无法供给 ${profile.name}"
        if (isReady(context, profile)) return "✅ ${profile.name} 已就绪（跳过安装）"
        
        android.util.Log.i("CmsEnvProvisioner", "开始供给 ${profile.name}，脚本长度: ${profile.installScript.length}，超时: ${profile.timeoutMs}ms")
        val startTime = System.currentTimeMillis()
        val (c, out) = QuroLinuxEnv.run(context, profile.installScript, timeoutMs = profile.timeoutMs)
        val duration = System.currentTimeMillis() - startTime
        android.util.Log.i("CmsEnvProvisioner", "${profile.name} 执行完成，耗时: ${duration}ms，退出码: $c")
        
        val ok = c == 0 || isReady(context, profile)
        if (ok) {
            QuroLinuxEnv.run(context, "mkdir -p $MARKER_DIR && touch $MARKER_DIR/${profile.name}.done", timeoutMs = 10_000)
            return "✅ ${profile.name} 安装完成（耗时 ${duration/1000}秒）\n${out}"
        }
        return "❌ ${profile.name} 安装失败(exit $c，耗时 ${duration/1000}秒)\n${out}"
    }

    /** 供给多个档（按档名），逐个执行并汇总（非致命）。返回 (档名, 结果)。 */
    fun provisionAll(context: Context, profiles: List<String>): List<Pair<String, String>> {
        return profiles.mapNotNull { name ->
            val p = EnvProfile.parse(name) ?: return@mapNotNull null
            p.name to provision(context, p)
        }
    }
}
