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
# Quro Engine bootstrap - one-time full dev environment under /root/cms/_engine.
set -e
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
        echo "[quro-engine] ERROR: failed to write DNS configuration"
        echo "[quro-engine] Current /etc directory contents:"
        ls -la /etc/ 2>/dev/null || echo "(empty)"
        exit 1
    fi
    
    echo "[quro-engine] DNS configured (fallback: 8.8.8.8, 114.114.114.114)"
else
    echo "[quro-engine] DNS already configured"
fi

# Phase 0.5: mirrors (skip if already configured by Android side)
echo "[quro-engine] checking apk mirrors..."
if [ ! -s /etc/apk/repositories ] || ! grep -q "alpine" /etc/apk/repositories 2>/dev/null; then
    mkdir -p /etc/apk
    printf 'https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.20/main\nhttps://mirrors.tuna.tsinghua.edu.cn/alpine/v3.20/community\nhttps://mirrors.aliyun.com/alpine/v3.20/main\nhttps://mirrors.aliyun.com/alpine/v3.20/community\nhttps://dl-cdn.alpinelinux.org/alpine/v3.20/main\nhttps://dl-cdn.alpinelinux.org/alpine/v3.20/community\n' > /etc/apk/repositories
    echo "[quro-engine] mirrors configured"
else
    echo "[quro-engine] mirrors already configured"
fi

# Phase 1: apk update with retry
echo "[quro-engine] updating apk index..."
if ! apk update --no-cache 2>&1; then
    echo "[quro-engine] WARN: apk update failed, trying alternative mirrors..."
    for m in tsinghua aliyun dl-cdn; do
        case "${'$'}m" in
            tsinghua) BASE="https://mirrors.tuna.tsinghua.edu.cn/alpine" ;;
            aliyun)   BASE="https://mirrors.aliyun.com/alpine" ;;
            dl-cdn)   BASE="https://dl-cdn.alpinelinux.org/alpine" ;;
        esac
        printf '%s/v3.20/main\n%s/v3.20/community\n' "${'$'}BASE" "${'$'}BASE" > /etc/apk/repositories
        sleep 1
        if apk update --no-cache 2>&1; then break; fi
    done
    # final check
    if ! apk update --no-cache 2>&1; then
        echo "[quro-engine] FAILED: apk update failed on all mirrors"
        exit 1
    fi
fi

# Phase 2: language runtimes
echo "[quro-engine] installing language runtimes..."
apk add --no-cache python3 py3-pip nodejs npm 2>&1 || {
    sleep 3
    apk add --no-cache python3 py3-pip nodejs npm 2>&1 || {
        echo "[quro-engine] FAILED: language runtimes install failed"
        exit 1
    }
}

# Phase 3: build toolchain
echo "[quro-engine] installing build toolchain..."
apk add --no-cache gcc g++ make cmake linux-headers 2>&1 || true

# Phase 4: dev tools
echo "[quro-engine] installing dev tools..."
apk add --no-cache git vim nano bash 2>&1 || true

# Phase 5: network tools
echo "[quro-engine] installing network tools..."
apk add --no-cache curl wget jq zip unzip openssh-client 2>&1 || true

# Phase 6: Python venv
if [ ! -x /root/cms-venv/bin/python3 ]; then
    echo "[quro-engine] creating /root/cms-venv..."
    python3 -m venv /root/cms-venv 2>&1 || echo "[quro-engine] WARN: venv creation failed"
fi

# Verify
echo "[quro-engine] dev environment ready:"
echo "  python3  = ${'$'}(python3 --version 2>&1)"
echo "  node     = ${'$'}(node --version 2>&1)"
echo "  gcc      = ${'$'}(gcc --version 2>&1 | head -1)"
echo "  git      = ${'$'}(git --version 2>&1)"

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
