package com.ai.assistance.quro.core.cms

import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import org.json.JSONObject
import java.security.MessageDigest

/**
 * CMS v2 CMS引擎（一级部署单元，区别于模块 [CmsDeployPackage]）。
 *
 * 「CMS引擎」是 CMS 原创性的核心：它不是某个业务模块，而是把整套终端运行引擎
 * （proot/Ubuntu 内的基础运行时 + 环境栈 + 共享服务）打包成可一键部署、可导出分享、
 * 可导入校验的官方署名包。模块依赖它提供的基础环境运行。
 *
 * 部署目标固定为 /root/cms/_engine（与模块 /root/cms/<moduleId> 隔离）。
 *
 * 完整性：sha256 为 manifest 规范摘要（部署前强制校验，P0）；signature 为发布者签名
 * （导入包须带，内置官方包由 [companion.builtin] 通道保证）。
 *
 * 注意：bootstrapContent / provisionerContent 内所有 shell `$` 必须以 `${'$'}` 转义，
 * 否则 Kotlin 原始字符串会把裸 `$` 当模板变量报 "Unresolved reference"。
 */
data class CmsEnginePackage(
    /** 引擎 id（固定内置为 "quro-engine"）。 */
    val engineId: String,
    val name: String,
    val engineVersion: String,
    /** 引擎引导脚本（sh，幂等）：装 python3/nodejs/基础工具 + 建目录 + 写就绪标记。 */
    val bootstrapContent: String,
    /** 环境供给脚本（sh，可选）：装配引擎级共享环境栈（区别于模块级 [CmsEnvProvisioner]）。 */
    val provisionerContent: String = "",
    /** 引擎提供的共享后台服务（如静态文件服务器 / 内部 API 网关）。 */
    val sharedServices: List<EngineSvc> = emptyList(),
    /** 引擎级环境档（NODE/PYTHON/SSH/JAVA/RUST/GO），部署时由 [CmsEnvProvisioner] 装配。 */
    val envProfiles: List<String> = emptyList(),
    val signature: String = "",
    val sha256: String = "",
) {
    /** 规范序列化（用于摘要计算，不含 sha256 自身）。 */
    fun canonical(): String = buildString {
        append(engineId); append("|"); append(name); append("|"); append(engineVersion); append("|")
        append(bootstrapContent); append("|"); append(provisionerContent); append("|")
        append(sharedServices.joinToString(";") { "${it.id}:${it.name}:${it.command}:${it.port}:${it.enabled}" }); append("|")
        append(envProfiles.joinToString(","))
    }

    /** 计算规范摘要（忽略 sha256 字段本身）。 */
    fun digest(): String = sha256Hex(canonical().toByteArray(Charsets.UTF_8))

    /** P0 完整性：声明 sha256 须与实算一致，否则拒部署（被篡改/损坏）。 */
    fun verifyIntegrity(): Boolean {
        if (sha256.isBlank()) return false
        return digest().equals(sha256, ignoreCase = true)
    }

    fun toJson(): String = JSONObject().apply {
        put("engineId", engineId)
        put("name", name)
        put("engineVersion", engineVersion)
        put("bootstrapContent", bootstrapContent)
        put("provisionerContent", provisionerContent)
        put("sharedServices", sharedServices.joinToString(";") { "${it.id}|${it.name}|${it.command}|${it.port}|${it.enabled}" })
        put("envProfiles", envProfiles.joinToString(","))
        put("signature", signature)
        put("sha256", sha256)
    }.toString()

    companion object {
        fun fromJson(s: String): CmsEnginePackage {
            val o = JSONObject(s)
            val svcs = o.optString("sharedServices", "").split(";").map { it.trim() }.filter { it.isNotBlank() }
                .map { seg ->
                    val p = seg.split("|")
                    EngineSvc(
                        id = p.getOrNull(0) ?: "",
                        name = p.getOrNull(1) ?: "",
                        command = p.getOrNull(2) ?: "",
                        port = p.getOrNull(3)?.toIntOrNull() ?: 0,
                        enabled = p.getOrNull(4)?.toBooleanStrictOrNull() ?: true,
                    )
                }
            return CmsEnginePackage(
                engineId = o.getString("engineId"),
                name = o.optString("name", o.getString("engineId")),
                engineVersion = o.optString("engineVersion", "1.0.0"),
                bootstrapContent = o.optString("bootstrapContent", ""),
                provisionerContent = o.optString("provisionerContent", ""),
                sharedServices = svcs,
                envProfiles = o.optString("envProfiles", "").split(",").map { it.trim() }.filter { it.isNotBlank() },
                signature = o.optString("signature", ""),
                sha256 = o.optString("sha256", ""),
            )
        }

        /** 给引擎包填上正确的 sha256（构建/发布内置包时用）。 */
        fun signed(pkg: CmsEnginePackage): CmsEnginePackage = pkg.copy(sha256 = pkg.digest())

        private fun sha256Hex(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(bytes).joinToString("") { "%02x".format(it) }
        }

        /**
         * 官方署名内置CMS引擎（quro-engine）。
         * 把 [CmsTerminalDeployer] 的 bootstrap 逻辑与引擎级共享环境内联为自包含引导脚本，
         * 部署到 /root/cms/_engine，提供全模块共享的基础运行时 + 一个静态文件服务（暴露模块目录）。
         */
        fun builtin(): CmsEnginePackage = signed(
            CmsEnginePackage(
                engineId = "quro-engine",
                name = "Zorv CMS引擎",
                engineVersion = "1.0.0",
                bootstrapContent = BUILTIN_BOOTSTRAP,
                provisionerContent = BUILTIN_PROVISIONER,
                sharedServices = listOf(
                    EngineSvc(
                        id = "cms-static",
                        name = "CMS 静态资源服务",
                        command = "cd /root/cms && nohup python3 -m http.server 8080 >/root/cms/_engine/services/cms-static.log 2>&1 &",
                        port = 8080,
                        enabled = true,
                    ),
                ),
                envProfiles = listOf("PYTHON", "NODE"),
            )
        )
    }
}

/** 引擎提供的共享后台服务描述（写入 /root/cms/_engine/services/<id>.sh 并后台拉起）。 */
data class EngineSvc(
    val id: String,
    val name: String,
    /** 启动命令模板（sh，应自行后台化：nohup ... &）。 */
    val command: String,
    val port: Int = 0,
    val enabled: Boolean = true,
)

/** 内置引擎引导脚本（幂等，与 assets/cms/bootstrap.sh 同源，便于引擎包独立分发）。 */
private val BUILTIN_BOOTSTRAP = QuroLinuxEnv.APT_LOCK_RELEASE_PROLOGUE + """
#!/bin/sh
# Quro Engine bootstrap - one-time full dev environment under /root/cms/_engine.
ENGINE_DIR=/root/cms/_engine
mkdir -p "${'$'}ENGINE_DIR/services"

# Phase 0: DNS
echo "[quro-engine] checking DNS..."

# 先检查/etc目录是否存在
if [ ! -d /etc ]; then
    echo "[quro-engine] WARN: /etc directory does not exist, creating..."
    mkdir -p /etc || { echo "[quro-engine] ERROR: failed to create /etc directory"; exit 1; }
fi

# 检查resolv.conf是否存在且包含nameserver
if [ ! -f /etc/resolv.conf ] || ! grep -q "nameserver" /etc/resolv.conf 2>/dev/null; then
    echo "[quro-engine] DNS not configured, writing resolv.conf..."
    # 尝试多种方式写入DNS配置
    if ! cat > /etc/resolv.conf << 'DNS'
nameserver 8.8.8.8
nameserver 8.8.4.4
nameserver 114.114.114.114
nameserver 223.5.5.5
nameserver 1.1.1.1
nameserver 9.9.9.9
DNS
    then
        # 如果cat失败，尝试echo方式
        echo "[quro-engine] WARN: cat failed, trying echo..."
        echo "nameserver 8.8.8.8" > /etc/resolv.conf
        echo "nameserver 8.8.4.4" >> /etc/resolv.conf
        echo "nameserver 114.114.114.114" >> /etc/resolv.conf
        echo "nameserver 223.5.5.5" >> /etc/resolv.conf
        echo "nameserver 1.1.1.1" >> /etc/resolv.conf
        echo "nameserver 9.9.9.9" >> /etc/resolv.conf
    fi
    
    # 验证写入是否成功
    if [ ! -f /etc/resolv.conf ] || ! grep -q "nameserver" /etc/resolv.conf 2>/dev/null; then
        echo "[quro-engine] WARN: failed to write DNS configuration, continuing anyway..."
        # 不退出，继续执行后续步骤
    else
        echo "[quro-engine] DNS configured (fallback: 8.8.8.8, 114.114.114.114)"
    fi
else
    echo "[quro-engine] DNS already configured"
fi

# Phase 0.2 (Bug4 加固)：绕过被阻断的 DNS 53 端口 —— 静态映射 apt 镜像域名到近期 anycast IP。
# proot 下 glibc 按 nsswitch `hosts: files dns` 先查 /etc/hosts，命中即直连 IP，不再发 53 查询。
# 与终端侧 bootstrapHosts 注入的 `# quro-dns-bootstrap` 条目共存、互为兜底（glibc 返回全部命中 IP 并依次尝试）。
# 全程 guarded：先删自己上一次写的标记行（幂等刷新），再追加；绝不覆盖用户/系统其它 hosts 条目。
# 即使所列 IP 偶发失效，setup 阶段注入的动态 IP 仍会被尝试，故仅为二次兜底，非致命。
sed -i '/# quro-engine-dns$/d' /etc/hosts 2>/dev/null || true
cat >> /etc/hosts << 'HOSTS'
163.181.201.182 mirrors.aliyun.com # quro-engine-dns
163.181.92.243 mirrors.aliyun.com # quro-engine-dns
101.6.15.130 mirrors.tuna.tsinghua.edu.cn # quro-engine-dns
91.189.91.39 ports.ubuntu.com # quro-engine-dns
HOSTS
echo "[quro-engine] DNS: appended static mirror mappings to /etc/hosts (bypass port 53)"

# Phase 0.5: apt sources (skip if already configured by Android side)
# 关键修复：已切换到 Ubuntu 24.04 (Noble) ARM64 rootfs。用 HTTP 镜像，避免 proot 下 CA 证书缺失导致 apt over HTTPS 失败。
echo "[quro-engine] checking apt sources..."
if [ ! -s /etc/apt/sources.list ] || ! grep -q "noble" /etc/apt/sources.list 2>/dev/null; then
    mkdir -p /etc/apt/apt.conf.d
    # 关闭签名验证（proot 环境下 GPG 公钥可能不完整）
    printf 'Acquire::Check-Valid-Until "false";\nAPT::Get::AllowUnauthenticated "true";\n' > /etc/apt/apt.conf.d/99no-check-gpg
    printf 'deb http://mirrors.aliyun.com/ubuntu-ports/ noble main restricted universe multiverse\ndeb http://mirrors.aliyun.com/ubuntu-ports/ noble-updates main restricted universe multiverse\ndeb http://mirrors.aliyun.com/ubuntu-ports/ noble-security main restricted universe multiverse\n' > /etc/apt/sources.list
    echo "[quro-engine] apt sources configured (noble, http)"
else
    echo "[quro-engine] apt sources already configured (keeping existing)"
fi

# Phase 0.6: 手动合并 CA 证书（update-ca-certificates 在 proot 下不可靠，避免后续 https 下载证书报错）
echo "[quro-engine] merging CA certificates..."
if [ -d /usr/share/ca-certificates/mozilla ]; then
    cat /usr/share/ca-certificates/mozilla/*.crt > /etc/ssl/certs/ca-certificates.crt 2>/dev/null || true
fi

# Phase 1: apt update with retry
echo "[quro-engine] updating apt index..."
if ! apt-get update 2>&1; then
    echo "[quro-engine] WARN: apt-get update failed, trying alternative mirrors..."
    for m in aliyun tsinghua ports; do
        case "${'$'}m" in
            aliyun)   BASE="https://mirrors.aliyun.com/ubuntu-ports" ;;
            tsinghua) BASE="https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports" ;;
            ports)    BASE="http://ports.ubuntu.com/ubuntu-ports" ;;
        esac
        printf "deb %s/ noble main restricted universe multiverse\ndeb %s/ noble-updates main restricted universe multiverse\ndeb %s/ noble-security main restricted universe multiverse\n" "${'$'}BASE" "${'$'}BASE" "${'$'}BASE" > /etc/apt/sources.list
        sleep 1
        if apt-get update 2>&1; then break; fi
    done
    # final check
    if ! apt-get update 2>&1; then
        echo "[quro-engine] FAILED: apt-get update failed on all mirrors"
        exit 1
    fi
fi

# Phase 2: 修复 dpkg 数据库错误
echo "[quro-engine] fixing dpkg database errors..."
dpkg --configure -a 2>/dev/null || true
apt-get install -f -y 2>/dev/null || true

# Phase 3: language runtimes
echo "[quro-engine] installing language runtimes..."
# 稳健安装函数：先 apt-get install；proot 下事务常半装失败，则回退 apt-get download + dpkg-deb -x。
robust_install() {
    local pkg="${'$'}1"
    local cmd="${'$'}2"
    if command -v ${'$'}cmd >/dev/null 2>&1; then
        echo "[quro-engine] ${'$'}pkg already installed: $(command -v ${'$'}cmd)"
        return 0
    fi
    echo "[quro-engine] Installing ${'$'}pkg..."
    apt-get install -y --no-install-recommends ${'$'}pkg 2>&1 | tail -3
    if command -v ${'$'}cmd >/dev/null 2>&1; then
        echo "[quro-engine] ✅ ${'$'}pkg installed via apt"
        return 0
    fi
    echo "[quro-engine] ⚠️ apt 未生效，${'$'}pkg 改用 download+dpkg-deb 回退..."
    local tmp=/tmp/quro_deb; mkdir -p "${'$'}tmp"
    ( cd "${'$'}tmp" && apt-get download ${'$'}pkg 2>/dev/null && for f in *.deb; do dpkg-deb -x "${'$'}f" / 2>/dev/null; done; rm -f *.deb )
    apt-get install -f -y 2>/dev/null || true
    if command -v ${'$'}cmd >/dev/null 2>&1; then
        echo "[quro-engine] ✅ ${'$'}pkg installed via dpkg fallback"
        return 0
    else
        echo "[quro-engine] ❌ ${'$'}pkg installation failed (apt + dpkg fallback)"
        return 1
    fi
}

# 安装语言运行时
robust_install "python3" "python3" || true
robust_install "python3-pip" "pip3" || true
# Node.js：优先 apt（robust_install 含 dpkg 回退）；若仍无效则用官方独立二进制（proot 下 apt nodejs 的 externalized builtins 易坏，见 CMS 引擎依赖修复报告）
robust_install "nodejs" "node" || true
if ! command -v node >/dev/null 2>&1; then
    echo "[quro-engine] ⚠️ apt nodejs 无效，改用 Node.js 20 官方二进制..."
    curl -fsSL "https://npmmirror.com/mirrors/node/v20.19.0/node-v20.19.0-linux-arm64.tar.xz" -o /tmp/node.tar.xz 2>/dev/null
    if [ -f /tmp/node.tar.xz ]; then
        tar -xf /tmp/node.tar.xz -C /usr/local --strip-components=1 2>/dev/null
        rm -f /tmp/node.tar.xz
    fi
fi
robust_install "npm" "npm" || true

# Phase 4: build toolchain
echo "[quro-engine] installing build toolchain..."
robust_install "gcc" "gcc" || true
robust_install "g++" "g++" || true
robust_install "make" "make" || true
robust_install "cmake" "cmake" || true
robust_install "linux-headers-generic" "make" || true

# Phase 5: dev tools
echo "[quro-engine] installing dev tools..."
robust_install "git" "git" || true
robust_install "vim" "vim" || true
robust_install "nano" "nano" || true
robust_install "bash" "bash" || true

# Phase 6: network tools
echo "[quro-engine] installing network tools..."
robust_install "curl" "curl" || true
robust_install "wget" "wget" || true
robust_install "jq" "jq" || true
robust_install "zip" "zip" || true
robust_install "unzip" "unzip" || true
robust_install "openssh-client" "ssh" || true

# Phase 6.2 (Bug3 修复)：网络诊断命令（ping/nslookup/dig/host/netstat/ifconfig/ip/ss）。
# proot 默认不含，按用户清单补齐；best-effort 非致命（|| true 兜底，绝不阻断整包部署）。
# 包映射：iputils-ping→ping, dnsutils→nslookup/dig/host, net-tools→netstat/ifconfig, iproute2→ip/ss
robust_install "iputils-ping" "ping" || true
robust_install "dnsutils" "nslookup" || true
robust_install "net-tools" "netstat" || true
robust_install "iproute2" "ip" || true

# Phase 6.5 (Bug3 修复)：bc 计算器（部分模块/诊断脚本依赖）。proot 下默认不包含，需显式安装。
# best-effort，失败非致命（用 || true 兜底，绝不阻断整包部署）。
robust_install "bc" "bc" || true

# Phase 7.x (轮次E · Rust 修复)：rustup 工具链软链 + 环境变量持久化（幂等兜底）
# 兼容两种布局：/var/rustup+/var/cargo（方案标准）与默认 /root/.rustup+/root/.cargo（dev-env/终端 UI 安装）。
# 仅当 rustup 工具链真实存在时清理孤儿并软链；否则保留 apt 装的 rustc/cargo，绝不破坏可用链路。
RUST_HOME=""
CARGO_HOME_DIR=""
if [ -d /var/rustup/toolchains ]; then RUST_HOME=/var/rustup; CARGO_HOME_DIR=/var/cargo; fi
if [ -d /root/.rustup/toolchains ]; then RUST_HOME=/root/.rustup; CARGO_HOME_DIR=/root/.cargo; fi
RUST_TC_DIR=""
if [ -n "${'$'}RUST_HOME" ]; then
    for d in "${'$'}RUST_HOME"/toolchains/*-unknown-linux-gnu "${'$'}RUST_HOME"/toolchains/stable-*; do
        if [ -d "${'$'}d/bin" ] && [ -x "${'$'}d/bin/rustc" ]; then RUST_TC_DIR="${'$'}d/bin"; break; fi
    done
fi
if [ -n "${'$'}RUST_TC_DIR" ]; then
    rm -f /usr/bin/rustc /usr/bin/cargo /usr/bin/rustfmt /usr/bin/clippy-driver /usr/bin/cargo-clippy 2>/dev/null || true
    for b in rustc cargo rustfmt clippy-driver cargo-clippy rustdoc; do
        ln -sf "${'$'}RUST_TC_DIR/${'$'}b" /usr/bin/${'$'}b 2>/dev/null || true
    done
    echo "[rust] linked toolchain from ${'$'}RUST_TC_DIR"
fi
if [ -n "${'$'}RUST_HOME" ]; then
    grep -q 'RUSTUP_HOME' /root/.bashrc 2>/dev/null || cat >> /root/.bashrc << RB
export RUSTUP_HOME=${'$'}RUST_HOME
export CARGO_HOME=${'$'}CARGO_HOME_DIR
export PATH="${'$'}CARGO_HOME_DIR/bin:/usr/local/go/bin:${'$'}PATH"
RB
fi

# Phase 7: Python venv
if [ ! -x /root/cms-venv/bin/python3 ]; then
    echo "[quro-engine] creating /root/cms-venv..."
    python3 -m venv /root/cms-venv 2>&1 || echo "[quro-engine] WARN: venv creation failed"
fi

# Verify — 仅当核心开发工具全部就绪才写就绪标记（否则引擎会"假成功"：报装好却无工具，正是"引擎装了没用"根因）
echo "[quro-engine] verifying core dev tools..."
CORE_OK=1
for t in python3 node gcc make cmake git curl; do
    if command -v ${'$'}t >/dev/null 2>&1; then
        echo "[quro-engine]   ✅ ${'$'}t = $(command -v ${'$'}t)"
    else
        echo "[quro-engine]   ❌ ${'$'}t MISSING"
        CORE_OK=0
    fi
done
if [ "${'$'}CORE_OK" = "1" ]; then
    touch "${'$'}ENGINE_DIR/.engine.ready"
    echo "[quro-engine] ✅ all core tools present, marker written: ${'$'}ENGINE_DIR/.engine.ready"
else
    echo "[quro-engine] ❌ core dev tools missing, NOT writing .engine.ready（引擎部署将报告未就绪，便于排查）"
fi
"""

/** 内置引擎级环境供给脚本（best-effort，失败不阻断）。 */
private val BUILTIN_PROVISIONER = """
#!/bin/sh
# Quro Engine provisioner — engine-level shared environment (best effort).
set -e
echo "[quro-engine] provisioning engine-level tools..."
apt-get install -y --no-install-recommends bash coreutils findutils grep sed gawk || true
echo "[quro-engine] engine provisioning done"
"""
