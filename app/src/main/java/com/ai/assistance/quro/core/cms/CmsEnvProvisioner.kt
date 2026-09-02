package com.ai.assistance.quro.core.cms

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.util.QuroDiag

/**
 * CMS v2 终端环境供给器（原创运行时 · 开发环境栈补齐）。
 *
 * 把"开发环境栈"作为可声明依赖（[DepKind.ENV]）落到 proot/Ubuntu aarch64 沙箱：
 * 模块在「添加模块」对话框勾选所需环境档（Node/Python/SSH/Java/Rust/Go），
 * 部署到终端时由本供给器按需装配。装配幂等（标记文件 + command -v 探活），
 * 且**非致命**：任一档失败仅记日志继续，避免单档网络问题阻断整包部署。
 *
 * 执行通道唯一 = proot（与 CmsTerminalDeployer 一致）；终端未就绪直接拒绝。
 *
 * 注意：installScript 内所有 shell `$` 必须以 `${'$'}` 转义——Kotlin 原始字符串
 * 仍会按模板解析裸 `$`，否则编译报 "Unresolved reference"。
 */
/**
 * 开发环境装配共享 prologue：先释放可能残留的 dpkg/apt 锁，再注入与引擎 bootstrap 同源的
 * [robust_install] 函数（apt 失败回退 apt-get download + dpkg-deb -x）。
 * 这样各 EnvProfile 脚本里的 `apt-get install` 不再静默吞错，proot 下半装失败的包能被兜底装上。
 */
private val ENV_INSTALL_PROLOGUE = QuroLinuxEnv.APT_LOCK_RELEASE_PROLOGUE + """
    |
    |# ── 中和 dpkg 服务管理器 ──
    |neutralize_dpkg_services() {
    |    mkdir -p /usr/local/sbin
    |    for b in start-stop-daemon invoke-rc.d update-rc.d service systemctl telinit initctl deb-systemd-helper deb-systemd-invoke; do
    |        printf '#!/bin/sh\nexit 0\n' > "/usr/local/sbin/${'$'}b" 2>/dev/null
    |        chmod +x "/usr/local/sbin/${'$'}b" 2>/dev/null || true
    |    done
    |    printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d 2>/dev/null || true
    |    chmod +x /usr/sbin/policy-rc.d 2>/dev/null || true
    |}
    |neutralize_dpkg_services
    |
    |# ── Ubuntu 24.04 Noble apt sources（幂等，已存在且含 noble 则跳过）──
    |if [ ! -s /etc/apt/sources.list ] || ! grep -q "noble" /etc/apt/sources.list 2>/dev/null; then
    |    mkdir -p /etc/apt/apt.conf.d
    |    # 轮次F：放开未签名仓库（手动 curl 拉索引，无 Release 签名）→ apt 才肯用这批索引
    |    printf 'Acquire::Check-Valid-Until "false";\nAPT::Get::AllowUnauthenticated "true";\nAcquire::AllowInsecureRepositories "true";\n' > /etc/apt/apt.conf.d/99no-check-gpg
    |    # 轮次F：切清华 TUNA（HTTP）；ports.ubuntu.com pool 整体 404 已弃用
    |    printf 'deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble main restricted universe multiverse\ndeb http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-updates main restricted universe multiverse\ndeb http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-security main restricted universe multiverse\n' > /etc/apt/sources.list
    |fi
    |dpkg --configure -a 2>/dev/null || true
    |# 轮次F · apt 索引（绕过 apt-get update 超时）：手动 curl 拉清华 TUNA 12 组件索引；失败回退 apt-get update(硬25s)
    |quro_manual_apt_index() {
    |    local BASE="http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/dists"
    |    local APTL="/var/lib/apt/lists"
    |    mkdir -p "${'$'}APTL" "${'$'}APTL/partial"
    |    rm -f "${'$'}APTL"/partial/* 2>/dev/null || true
    |    local ok=0 total=0
    |    if command -v curl >/dev/null 2>&1; then
    |        for dist in noble noble-updates noble-security; do
    |            for comp in main universe multiverse restricted; do
    |                total=$((total+1))
    |                local f="mirrors.tuna.tsinghua.edu.cn_ubuntu-ports_dists_${'$'}dist_${'$'}comp_binary-arm64_Packages"
    |                if curl -s --max-time 40 -o "${'$'}APTL/${'$'}f.gz" "${'$'}BASE/${'$'}dist/${'$'}comp/binary-arm64/Packages.gz" \
    |                   && gzip -dc "${'$'}APTL/${'$'}f.gz" > "${'$'}APTL/${'$'}f" 2>/dev/null && [ -s "${'$'}APTL/${'$'}f" ]; then
    |                    rm -f "${'$'}APTL/${'$'}f.gz"; ok=$((ok+1)); echo "[apt] index ok: ${'$'}f"
    |                else
    |                    echo "[apt] WARN: failed fetch ${'$'}dist/${'$'}comp"; rm -f "${'$'}APTL/${'$'}f.gz" "${'$'}APTL/${'$'}f" 2>/dev/null || true
    |                fi
    |            done
    |        done
    |        echo "[apt] manual index: ${'$'}ok/${'$'}total fetched"
    |        if [ "${'$'}ok" -ge 1 ]; then
    |            rm -f "${'$'}APTL"/ports.ubuntu.com_* 2>/dev/null || true
    |            rm -f "${'$'}APTL"/mirrors.aliyun.com_* 2>/dev/null || true
    |        fi
    |    else
    |        echo "[apt] curl not available, will rely on apt-get update"
    |    fi
    |    if [ "${'$'}ok" -lt 1 ]; then
    |        echo "[apt] manual index insufficient, trying apt-get update (hard 25s timeout)..."
    |        if command -v timeout >/dev/null 2>&1; then
    |            timeout 25 apt-get update 2>&1 | tail -5 || true
    |        else
    |            apt-get update 2>&1 | tail -5 || true
    |        fi
    |    fi
    |}
    |quro_manual_apt_index
    |
    |# ── 稳健安装函数：apt 优先，失败回退 dpkg-deb -x ──
    |robust_install() {
    |    local pkgs="${'$'}1"
    |    local probe="${'$'}2"
    |    if [ -n "${'$'}probe" ] && command -v "${'$'}probe" >/dev/null 2>&1; then
    |        return 0
    |    fi
    |    echo "[env] Installing ${'$'}pkgs..."
    |    apt-get install -y --no-install-recommends ${'$'}pkgs 2>&1 | tail -5
    |    if [ -n "${'$'}probe" ] && command -v "${'$'}probe" >/dev/null 2>&1; then
    |        return 0
    |    fi
    |    echo "[env] apt failed, trying dpkg fallback..."
    |    local tmp=/tmp/quro_deb; mkdir -p "${'$'}tmp"
    |    ( cd "${'$'}tmp" && apt-get download ${'$'}pkgs 2>/dev/null; for f in *.deb; do [ -e "${'$'}f" ] && dpkg-deb -x "${'$'}f" / 2>/dev/null; done; rm -f *.deb )
    |    apt-get install -f -y 2>/dev/null || true
    |    if [ -n "${'$'}probe" ] && command -v "${'$'}probe" >/dev/null 2>&1; then
    |        return 0
    |    elif [ -z "${'$'}probe" ]; then
    |        return 0
    |    else
    |        return 1
    |    fi
    |}
""".trimMargin()

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
        |robust_install "nodejs npm" "node" || echo "[env] WARN: nodejs/npm install failed"
        |npm config set prefix /usr/local 2>/dev/null || true
        |npm config set cache /tmp/npm-cache 2>/dev/null || true
        |export PATH="/usr/local/bin:${'$'}PATH"
        |npm install -g pnpm typescript 2>&1 | tail -5 || echo "WARN: npm global install failed"
        |echo "[env] node=${'$'}(node --version 2>&1) pnpm=${'$'}(which pnpm 2>/dev/null || echo 'not found')"
        """.trimMargin(),
    ),
    PYTHON(
        "Python 栈 (python3 / venv / pip)",
        // Bug2 修复：去掉强制要求 uv（Rust 安装器，proot 下常下载失败 → 误判“PYTHON 未注册”）。
        // 只要 python3 + pip/pip3 可用即视为就绪；uv 作为独立 UV 档按需装配。
        "command -v python3 >/dev/null 2>&1 && (command -v pip >/dev/null 2>&1 || command -v pip3 >/dev/null 2>&1)",
        """
        |robust_install "python3 python3-pip python3-venv" "python3" || echo "[env] WARN: python3/pip/venv install failed"
        |if ! command -v python >/dev/null 2>&1; then ln -sf ${'$'}(command -v python3) /usr/local/bin/python 2>/dev/null || true; fi
        |python3 -m ensurepip --upgrade 2>/dev/null || true
        |# Bug2 修复：venv 目录优先建在 /opt（避开 proot /root ACL 限制），失败再退回 ${'$'}HOME。
        |python3 -m venv /opt/cms-venv 2>/dev/null || python3 -m venv "${'$'}HOME/cms-venv" 2>/dev/null || true
        |echo "[env] python=${'$'}(python --version 2>&1) pip=${'$'}(pip --version 2>&1 || pip3 --version 2>&1)"
        """.trimMargin(),
    ),
    SSH(
        "SSH 工具链 (openssh + sshpass + sshd)",
        // 轮次F 修复：原 checkCmd 要求 ssh && sshpass && sshd 三者俱全，但终端侧仅装 openssh-client（ssh 客户端），
        // 导致 /usr/bin/ssh 已存在时 status_devenv 仍误报“❌ 未安装”（与 Bug8 Python 探测同类：检测逻辑与实际环境不一致）。
        // 顶层 SSH 档以「ssh 客户端可用」为就绪信号；sshpass/sshd 由 SSHPASS/SSH_SERVER 子档独立探测。
        "command -v ssh >/dev/null 2>&1",
        """
        |robust_install "openssh-client openssh-server sshpass" "ssh" || echo "[env] WARN: ssh install failed"
        |if [ ! -f /etc/ssh/ssh_host_rsa_key ]; then ssh-keygen -A 2>/dev/null || true; fi
        |echo "[env] ssh=${'$'}(ssh -V 2>&1) sshpass=${'$'}(sshpass -V 2>&1 | head -1)"
        """.trimMargin(),
    ),
    JAVA(
        "Java 栈 (OpenJDK 17 + Gradle)",
        "command -v java >/dev/null 2>&1 && command -v gradle >/dev/null 2>&1",
        """
        |robust_install "openjdk-17-jdk-headless" "java" || echo "[env] WARN: JDK17 install failed"
        |robust_install "gradle" "gradle" || echo "[env] WARN: Gradle install failed"
        |echo "[env] java=${'$'}(java -version 2>&1 | head -1)"
        """.trimMargin(),
    ),
    RUST(
        "Rust / Cargo",
        "command -v rustc >/dev/null 2>&1 && command -v cargo >/dev/null 2>&1",
        """
        |robust_install "rustc cargo" "rustc" || echo "[env] WARN: rust/cargo install failed"
        |# ── 轮次E Rust 修复：rustup 工具链软链 + 环境变量持久化（幂等兜底）──
        |# 兼容两种布局：/var/rustup+/var/cargo（方案标准）与默认 /root/.rustup+/root/.cargo（dev-env/终端 UI 安装）。
        |# 仅当 rustup 工具链真实存在时清理 /usr/bin 孤儿二进制并软链；否则保留 apt 装的 rustc/cargo，绝不破坏可用链路。
        |RUST_HOME=""
        |CARGO_HOME_DIR=""
        |if [ -d /var/rustup/toolchains ]; then RUST_HOME=/var/rustup; CARGO_HOME_DIR=/var/cargo; fi
        |if [ -d /root/.rustup/toolchains ]; then RUST_HOME=/root/.rustup; CARGO_HOME_DIR=/root/.cargo; fi
        |RUST_TC_DIR=""
        |if [ -n "${'$'}RUST_HOME" ]; then
        |    for d in "${'$'}RUST_HOME"/toolchains/*-unknown-linux-gnu "${'$'}RUST_HOME"/toolchains/stable-*; do
        |        if [ -d "${'$'}d/bin" ] && [ -x "${'$'}d/bin/rustc" ]; then RUST_TC_DIR="${'$'}d/bin"; break; fi
        |    done
        |fi
        |if [ -n "${'$'}RUST_TC_DIR" ]; then
        |    rm -f /usr/bin/rustc /usr/bin/cargo /usr/bin/rustfmt /usr/bin/clippy-driver /usr/bin/cargo-clippy 2>/dev/null || true
        |    for b in rustc cargo rustfmt clippy-driver cargo-clippy rustdoc; do
        |        ln -sf "${'$'}RUST_TC_DIR/${'$'}b" /usr/bin/${'$'}b 2>/dev/null || true
        |    done
        |    echo "[env] Rust: linked toolchain from ${'$'}RUST_TC_DIR"
        |fi
        |if [ -n "${'$'}RUST_HOME" ]; then
        |    grep -q 'RUSTUP_HOME' /root/.bashrc 2>/dev/null || cat >> /root/.bashrc << RB
        |export RUSTUP_HOME=${'$'}RUST_HOME
        |export CARGO_HOME=${'$'}CARGO_HOME_DIR
        |export PATH="${'$'}CARGO_HOME_DIR/bin:/usr/local/go/bin:${'$'}PATH"
        |RB
        |fi
        |echo "[env] rustc=${'$'}(rustc --version 2>&1) cargo=${'$'}(cargo --version 2>&1)"
        """.trimMargin(),
    ),
    GO(
        "Go 语言栈 (Go)",
        "command -v go >/dev/null 2>&1",
        """
        |robust_install "golang-go" "go" || echo "[env] WARN: golang install failed"
        |echo "[env] go=${'$'}(go version 2>&1)"
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
        |echo "[env] python=${'$'}(python --version 2>&1)"
        """.trimMargin(),
    ),
    PIP(
        "Pip (Python 包管理器)",
        "command -v pip >/dev/null 2>&1 || command -v pip3 >/dev/null 2>&1",
        """
        |robust_install "python3-pip python3-venv" "pip3" || echo "[env] WARN: pip/venv install failed"
        |python3 -m ensurepip --upgrade 2>/dev/null || true
        |echo "[env] pip=${'$'}(pip --version 2>&1 || pip3 --version 2>&1)"
        """.trimMargin(),
    ),
    UV(
        "UV (Rust 极速 Python 包安装器)",
        "command -v uv >/dev/null 2>&1",
        """
        |robust_install "curl" "curl" || echo "[env] WARN: curl install failed"
        |if ! command -v uv >/dev/null 2>&1; then
        |  rm -rf "${'$'}HOME/.local/bin/uv" 2>/dev/null || true
        |  curl -LsSf https://astral.sh/uv/install.sh | sh 2>&1 | tail -3 || true
        |  ln -sf "${'$'}HOME/.local/bin/uv" /usr/local/bin/uv 2>/dev/null || true
        |fi
        |echo "[env] uv=${'$'}(uv --version 2>&1)"
        """.trimMargin(),
    ),
    VENV(
        "Python 虚拟环境 (venv)",
        // Bug2 修复：proot /root 受 ACL 限制时 /root/cms-venv 创建/探测失败 → VENV 档误判未就绪。
        // 允许 /opt/cms-venv 或 ${'$'}HOME/cms-venv（与 PYTHON 档优先写入路径一致）。
        "test -x /opt/cms-venv/bin/python3 || test -x \"\${'$'}HOME/cms-venv/bin/python3\" || test -x /root/cms-venv/bin/python3",
        """
        |VENV_ROOT=/opt/cms-venv
        |if [ ! -x "\${'$'}VENV_ROOT/bin/python3" ]; then
        |  python3 -m venv "\${'$'}VENV_ROOT" 2>/dev/null || python3 -m venv "\${'$'}HOME/cms-venv" 2>/dev/null || true
        |fi
        |echo "[env] venv=${'$'}(ls -d /opt/cms-venv "\${'$'}HOME/cms-venv" /root/cms-venv 2>/dev/null | head -1)/bin/python3"
        """.trimMargin(),
    ),
    // ─── Node.js 子环境 ───
    NODEJS(
        "Node.js (JavaScript 运行时)",
        "command -v node >/dev/null 2>&1",
        """
        |robust_install "nodejs npm" "node" || echo "[env] WARN: nodejs/npm install failed"
        |echo "[env] node=${'$'}(node --version 2>&1)"
        """.trimMargin(),
    ),
    PNPM(
        "PNPM (快速包管理器 + TypeScript)",
        "command -v pnpm >/dev/null 2>&1",
        """
        |robust_install "nodejs npm" "node" || echo "[env] WARN: nodejs/npm install failed"
        |npm config set prefix /usr/local 2>/dev/null || true
        |npm config set cache /tmp/npm-cache 2>/dev/null || true
        |export PATH="/usr/local/bin:${'$'}PATH"
        |npm install -g pnpm typescript 2>&1 | tail -5 || echo "WARN: npm global install failed"
        |echo "[env] pnpm=${'$'}(which pnpm 2>/dev/null || echo 'not found') tsc=${'$'}(which tsc 2>/dev/null || echo 'not found')"
        """.trimMargin(),
    ),
    // ─── SSH 子环境 ───
    SSH_CLIENT(
        "SSH 客户端 (openssh)",
        "command -v ssh >/dev/null 2>&1",
        """
        |robust_install "openssh-client" "ssh" || echo "[env] WARN: openssh-client install failed"
        |echo "[env] ssh=${'$'}(ssh -V 2>&1)"
        """.trimMargin(),
    ),
    SSHPASS(
        "sshpass (密码认证工具)",
        "command -v sshpass >/dev/null 2>&1",
        """
        |robust_install "sshpass" "sshpass" || echo "[env] WARN: sshpass install failed"
        |echo "[env] sshpass=${'$'}(sshpass -V 2>&1 | head -1)"
        """.trimMargin(),
    ),
    SSH_SERVER(
        "OpenSSH 服务器 (反向隧道)",
        "command -v sshd >/dev/null 2>&1",
        """
        |robust_install "openssh-server" "sshd" || echo "[env] WARN: openssh-server install failed"
        |if [ ! -f /etc/ssh/ssh_host_rsa_key ]; then ssh-keygen -A 2>/dev/null || true; fi
        |echo "[env] sshd=${'$'}(sshd -V 2>&1 | head -1)"
        """.trimMargin(),
    ),
    // ─── Java 子环境 ───
    OPENJDK17(
        "OpenJDK 17 (Java 开发环境)",
        "command -v java >/dev/null 2>&1",
        """
        |robust_install "openjdk-17-jdk-headless" "java" || echo "[env] WARN: OpenJDK17 install failed"
        |echo "[env] java=${'$'}(java -version 2>&1 | head -1)"
        """.trimMargin(),
    ),
    GRADLE(
        "Gradle (构建自动化工具)",
        "command -v gradle >/dev/null 2>&1",
        """
        |robust_install "gradle" "gradle" || echo "[env] WARN: Gradle install failed"
        |echo "[env] gradle=${'$'}(gradle --version 2>&1 | head -3)"
        """.trimMargin(),
    ),
    // ─── MCP 部署环境 ───
    MCP_SERVER(
        "MCP 服务器环境 (Node.js + MCP SDK)",
        "command -v node >/dev/null 2>&1 && npm list -g @modelcontextprotocol/sdk >/dev/null 2>&1",
        """
        |robust_install "nodejs npm" "node" || echo "[env] WARN: nodejs/npm install failed"
        |npm install -g @modelcontextprotocol/sdk 2>&1 | tail -5 || true
        |echo "[env] node=${'$'}(node --version 2>&1)"
        """.trimMargin(),
    ),
    MCP_CLIENT(
        "MCP 客户端环境 (Python + MCP Python SDK)",
        "command -v python >/dev/null 2>&1 && python -c 'import mcp' >/dev/null 2>&1",
        """
        |robust_install "python3 python3-pip python3-venv" "python3" || echo "[env] WARN: python3/pip/venv install failed"
        |pip install mcp 2>&1 | tail -5 || true
        |echo "[env] python=${'$'}(python --version 2>&1)"
        """.trimMargin(),
    ),
    MCP_TOOLS(
        "MCP 工具开发环境 (TypeScript + MCP 工具模板)",
        "command -v tsc >/dev/null 2>&1 && test -d /root/mcp-tools",
        """
        |robust_install "nodejs npm" "node" || echo "[env] WARN: nodejs/npm install failed"
        |npm install -g typescript 2>&1 | tail -5 || true
        |mkdir -p /root/mcp-tools && cd /root/mcp-tools
        |cat > package.json << 'EOF'
        |{"name":"mcp-tools","version":"1.0.0","dependencies":{"@modelcontextprotocol/sdk":"^1.0.0","typescript":"^5.0.0"}}
        |EOF
        |npm install 2>&1 | tail -3 || true
        |echo "[mcp] tsc=${'$'}(tsc --version 2>&1) tools-ready=${'$'}(test -d /root/mcp-tools/node_modules && echo yes || echo no)"
        """.trimMargin(),
    ),
    MCP_DEPLOY(
        "MCP 部署环境 (完整 MCP 服务器 + 客户端 + 工具)",
        "command -v node >/dev/null 2>&1 && command -v python >/dev/null 2>&1 && test -d /root/mcp-server",
        """
        |robust_install "nodejs npm python3 python3-pip" "node" || echo "[env] WARN: MCP deps install failed"
        |npm install -g @modelcontextprotocol/sdk typescript 2>&1 | tail -3 || true
        |pip install mcp 2>&1 | tail -3 || true
        |mkdir -p /root/mcp-server /root/mcp-tools && cd /root/mcp-server
        |cat > server.ts << 'EOF'
        |import { Server } from '@modelcontextprotocol/sdk/server/index.js';
        |import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
        |const server = new Server({ name: 'quro-mcp-server', version: '1.0.0' }, { capabilities: { tools: {} } });
        |server.setRequestHandler('tools/list', async () => ({ tools: [{ name: 'hello', description: 'Hello tool', inputSchema: { type: 'object', properties: {} } }] }));
        |server.setRequestHandler('tools/call', async (req) => { if (req.params.name === 'hello') return { content: [{ type: 'text', text: 'Hello from Quro MCP Server!' }] }; throw new Error('Unknown tool'); });
        |async function main() { const t = new StdioServerTransport(); await server.connect(t); console.error('Quro MCP Server running'); }
        |main().catch(console.error);
        |EOF
        |cat > start.sh << 'EOF'
        |#!/bin/sh
        |cd /root/mcp-server && npx tsx server.ts
        |EOF
        |chmod +x start.sh
        |echo "[mcp] server ready, tools ready"
        """.trimMargin(),
    );

    companion object {
        /** 按档名（不区分大小写）解析；无法识别返回 null。 */
        fun parse(name: String): EnvProfile? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

        /** 获取环境的安装脚本（包含 prologue）。 */
        fun getInstallScript(profile: EnvProfile): String {
            return ENV_INSTALL_PROLOGUE + "\n" + profile.installScript
        }
    }
}

object CmsEnvProvisioner {

    private const val MARKER_DIR = "/root/.cms-env"

    /** 把开发环境装配/检测的原始输出落盘到手机公共 Download/QuroAI_logs，用户无需 adb 即可取。 */
    private fun persistDiag(profile: EnvProfile, phase: String, out: String) {
        QuroDiag.log("DevEnv", "[$phase] ${profile.name}: ${out.take(800)}")
        QuroDiag.writeFile("quro_devenv_${profile.name}.log", "[$phase @ ${System.currentTimeMillis()}]\n$out\n")
    }

    /** 单个档是否已就绪（proot 内：以实际 command -v 探活为准，不轻信标记文件）。 */
    fun isReady(context: Context, profile: EnvProfile): Boolean {
        // 运行检查命令 — 先设置 PATH 确保 npm 全局包可被找到
        val diagnosticScript = buildString {
            append("export PATH=\"/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin:${'$'}PATH\"\n")
            append("echo '--- ${profile.name} 环境检测 ---'\n")
            append("echo \"PATH=${'$'}PATH\"\n")
            val cmds = profile.checkCmd.split(" && ").map { it.trim() }
            for (cmd in cmds) {
                append("if $cmd; then echo \"✅ PASS: $cmd\"; else echo \"❌ FAIL: $cmd\"; fi\n")
            }
            append("echo '--- 检测完成 ---'")
        }
        val (c, out) = QuroLinuxEnv.run(context, diagnosticScript, timeoutMs = 20_000)
        android.util.Log.i("CmsEnvProvisioner", "${profile.name}: checkCmd exit=$c, output=${out.take(500)}")
        persistDiag(profile, "detect", out)
        // 如果所有子命令都通过（输出中无 FAIL），则认为就绪
        val ready = !out.contains("FAIL:")
        android.util.Log.i("CmsEnvProvisioner", "${profile.name}: ready=$ready")
        // 以实际探测为准：就绪则补写标记；未就绪则清理可能的陈旧/假标记，避免误报「已就绪」。
        if (ready) {
            QuroLinuxEnv.run(context, "mkdir -p $MARKER_DIR && touch $MARKER_DIR/${profile.name}.done", timeoutMs = 10_000)
        } else {
            QuroLinuxEnv.run(context, "rm -f $MARKER_DIR/${profile.name}.done", timeoutMs = 10_000)
        }
        return ready
    }

    /** 供给单个档：已就绪/已标记则跳过；否则跑装配脚本（best-effort）。返回人类可读结果。 */
    fun provision(context: Context, profile: EnvProfile): String {
        if (isReady(context, profile)) return "✅ ${profile.name} 已就绪（跳过安装）"

        android.util.Log.i("CmsEnvProvisioner", "开始供给 ${profile.name}，脚本长度: ${profile.installScript.length}，超时: ${profile.timeoutMs}ms")
        val startTime = System.currentTimeMillis()
        // 安装前先释放残留 dpkg/apt 锁；并注入 robust_install 让 apt 失败可回退。
        // 注意：installScript 以 echo 结尾，其退出码恒为 0，绝不能用 c==0 判定成功，必须以实际探测为准。
        val script = ENV_INSTALL_PROLOGUE + "\n" + profile.installScript
        val (c, out) = QuroLinuxEnv.run(context, script, timeoutMs = profile.timeoutMs)
        val duration = System.currentTimeMillis() - startTime
        android.util.Log.i("CmsEnvProvisioner", "${profile.name} 执行完成，耗时: ${duration}ms，退出码: $c")
        persistDiag(profile, "provision(exit=$c,${duration}ms)", out)

        // 以实际探测（command -v）为准，isReady 内部会写/清标记。
        val ok = isReady(context, profile)
        if (ok) {
            android.util.Log.i("CmsEnvProvisioner", "${profile.name}: 探测通过，已就绪")
            return "✅ ${profile.name} 安装完成（耗时 ${duration/1000}秒）\n${out}"
        }
        return "❌ ${profile.name} 安装失败(脚本退出 $c，探测未通过，耗时 ${duration/1000}秒)\n${out}"
    }

    /** 带实时日志的供给单个档。返回人类可读结果。 */
    fun provisionWithLog(
        context: Context,
        profile: EnvProfile,
        onLine: (String) -> Unit
    ): String {
        if (isReady(context, profile)) return "✅ ${profile.name} 已就绪（跳过安装）"

        android.util.Log.i("CmsEnvProvisioner", "开始供给 ${profile.name}，超时: ${profile.timeoutMs}ms")
        onLine("[${profile.name}] 开始安装...")
        val startTime = System.currentTimeMillis()
        // 安装前先释放残留 dpkg/apt 锁；并注入 robust_install 让 apt 失败可回退。
        val script = ENV_INSTALL_PROLOGUE + "\n" + profile.installScript
        val (c, out) = QuroLinuxEnv.runWithLog(context, script, timeoutMs = profile.timeoutMs) { line ->
            onLine(line)
        }
        persistDiag(profile, "provisionWithLog(exit=$c)", out)
        val duration = System.currentTimeMillis() - startTime
        android.util.Log.i("CmsEnvProvisioner", "${profile.name} 执行完成，耗时: ${duration}ms，退出码: $c")

        // 以实际探测（command -v）为准，isReady 内部会写/清标记。
        val ok = isReady(context, profile)
        if (ok) {
            android.util.Log.i("CmsEnvProvisioner", "${profile.name}: 探测通过，已就绪")
            return "✅ ${profile.name} 安装完成（耗时 ${duration/1000}秒）"
        }
        return "❌ ${profile.name} 安装失败(脚本退出 $c，探测未通过，耗时 ${duration/1000}秒)"
    }

    /** 供给多个档（按档名），逐个执行并汇总（非致命）。返回 (档名, 结果)。 */
    fun provisionAll(context: Context, profiles: List<String>): List<Pair<String, String>> {
        return profiles.mapNotNull { name ->
            val p = EnvProfile.parse(name) ?: return@mapNotNull null
            p.name to provision(context, p)
        }
    }
}
