package com.ai.assistance.quro.core.cms

import org.json.JSONObject
import java.security.MessageDigest

/**
 * CMS v2 CMS引擎（一级部署单元，区别于模块 [CmsDeployPackage]）。
 *
 * 「CMS引擎」是 CMS 原创性的核心：它不是某个业务模块，而是把整套终端运行引擎
 * （proot/Alpine 内的基础运行时 + 环境栈 + 共享服务）打包成可一键部署、可导出分享、
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
private val BUILTIN_BOOTSTRAP = """
#!/bin/sh
# Quro Engine bootstrap — one-time full dev environment under /root/cms/_engine (proot/Alpine aarch64).
set -e
ENGINE_DIR=/root/cms/_engine
mkdir -p "${'$'}ENGINE_DIR/services"

# ═══ Phase 0: 配置 DNS ═══
echo "[quro-engine] 🔧 configuring DNS..."
if [ ! -f /etc/resolv.conf ] || ! grep -q "nameserver" /etc/resolv.conf 2>/dev/null; then
    mkdir -p /etc
    cat > /etc/resolv.conf << 'DNS'
nameserver 8.8.8.8
nameserver 8.8.4.4
nameserver 114.114.114.114
nameserver 223.5.5.5
DNS
    echo "[quro-engine] DNS configured"
fi

# ═══ Phase 0.5: 配置镜像源 ═══
echo "[quro-engine] 🔧 configuring apk mirrors..."
cat > /etc/apk/repositories << 'MIRRORS'
https://mirrors.aliyun.com/alpine/v3.20/main
https://mirrors.aliyun.com/alpine/v3.20/community
https://dl-cdn.alpinelinux.org/alpine/v3.20/main
https://dl-cdn.alpinelinux.org/alpine/v3.20/community
MIRRORS

# ═══ Phase 1: 更新索引（带重试） ═══
echo "[quro-engine] 📦 updating apk index..."
update_apk() {
    apk update --no-cache 2>&1 && return 0
    echo "[quro-engine] WARN: apk update failed, trying alternative mirrors..."
    echo "https://dl-cdn.alpinelinux.org/alpine/v3.20/main" > /etc/apk/repositories
    echo "https://dl-cdn.alpinelinux.org/alpine/v3.20/community" >> /etc/apk/repositories
    sleep 2
    apk update --no-cache 2>&1 && return 0
    echo "https://mirrors.aliyun.com/alpine/v3.20/main" > /etc/apk/repositories
    sleep 2
    apk update --no-cache 2>&1 && return 0
    return 1
}
update_apk || {
    echo "[quro-engine] ❌ FAILED: apk update failed on all mirrors"
    exit 1
}

# ═══ Phase 2: 语言运行时 ═══
echo "[quro-engine] 📦 installing language runtimes..."
install_with_retry() {
    local pkgs="$1"
    local max_retries=3
    local retry=0
    while [ ${'$'}retry -lt ${'$'}max_retries ]; do
        apk add --no-cache ${'$'}pkgs 2>&1 && return 0
        retry=$((${'$'}retry + 1))
        echo "[quro-engine] WARN: attempt ${'$'}retry/${'$'}max_retries failed, retrying in 3s..."
        sleep 3
    done
    return 1
}
install_with_retry "python3 py3-pip nodejs npm" || {
    echo "[quro-engine] ❌ FAILED: language runtimes install failed"
    exit 1
}

# ═══ Phase 3: 编译工具链 ═══
echo "[quro-engine] 🔨 installing build toolchain..."
install_with_retry "gcc g++ make cmake linux-headers" || true

# ═══ Phase 4: 开发工具 ═══
echo "[quro-engine] 🛠️ installing dev tools..."
install_with_retry "git vim nano bash" || true

# ═══ Phase 5: 网络与压缩工具 ═══
echo "[quro-engine] 🌐 installing network & utility tools..."
install_with_retry "curl wget jq zip unzip openssh-client" || true

# ═══ Phase 6: Python venv ═══
if [ ! -x /root/cms-venv/bin/python3 ]; then
    echo "[quro-engine] creating /root/cms-venv..."
    python3 -m venv /root/cms-venv || echo "[quro-engine] WARN: venv creation failed"
fi

# ═══ 验证 ═══
echo "[quro-engine] ✅ dev environment ready:"
echo "  python3  = ${'$'}(python3 --version 2>&1)"
echo "  node     = ${'$'}(node --version 2>&1)"
echo "  npm      = ${'$'}(npm --version 2>&1)"
echo "  gcc      = ${'$'}(gcc --version 2>&1 | head -1)"
echo "  cmake    = ${'$'}(cmake --version 2>&1 | head -1)"
echo "  git      = ${'$'}(git --version 2>&1)"
echo "  curl     = ${'$'}(curl --version 2>&1 | head -1)"

touch "${'$'}ENGINE_DIR/.engine.ready"
echo "[quro-engine] marker written: ${'$'}ENGINE_DIR/.engine.ready"
"""

/** 内置引擎级环境供给脚本（best-effort，失败不阻断）。 */
private val BUILTIN_PROVISIONER = """
#!/bin/sh
# Quro Engine provisioner — engine-level shared environment (best effort).
set -e
echo "[quro-engine] provisioning engine-level tools..."
apk add --no-cache bash coreutils findutils grep sed gawk || true
echo "[quro-engine] engine provisioning done"
"""
