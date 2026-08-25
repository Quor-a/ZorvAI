package com.ai.assistance.quro.core.cms

import org.json.JSONObject
import java.security.MessageDigest

/**
 * CMS v2 终端部署包 manifest（原创运行时 · 部署器数据单元）。
 *
 * 一个「部署包」描述一个要推到 proot/Ubuntu 沙箱（即"终端"）运行的模块：
 * - 入口脚本 [entryContent]（如 python3 脚本 / shell 脚本）
 * - 依赖：apt 包 [apkDeps]（apt-get install -y）/ Python 包 [pipDeps]（pip install）
 * - 环境变量 [env]、监听端口 [ports]
 * - **完整性**：[sha256] 为 manifest 规范摘要（部署前强制校验，P0）；[signature] 为发布者签名（导入包须带）。
 *
 * 注意：本结构是 CMS 专属运行时（proot/QuickJS/权限系统之上的原创胶水层）的一部分，
 * 不是从零重写沙箱/内核。
 */
data class CmsDeployPackage(
    val moduleId: String,
    val name: String,
    val version: String,
    /** 入口脚本相对文件名，如 "entry.py"。 */
    val entry: String,
    /** 入口脚本内容（空表示入口由外部提供，仅占位）。 */
    val entryContent: String,
    val apkDeps: List<String> = emptyList(),
    val pipDeps: List<String> = emptyList(),
    val envProfiles: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val ports: List<Int> = emptyList(),
    /** 发布者签名（导入包必须非空；内置包可空，由内置签名通道保证）。 */
    val signature: String = "",
    /** manifest 规范摘要 SHA-256（部署前校验，防篡改/损坏）。 */
    val sha256: String = "",
) {
    /** 规范序列化（用于摘要计算，不含 sha256 自身）。 */
    fun canonical(): String = buildString {
        append(moduleId); append("|"); append(name); append("|"); append(version); append("|")
        append(entry); append("|"); append(entryContent); append("|")
        append(apkDeps.joinToString(",")); append("|"); append(pipDeps.joinToString(",")); append("|")
        append(envProfiles.joinToString(",")); append("|")
        append(env.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }); append("|")
        append(ports.joinToString(","))
    }

    /** 计算规范摘要（忽略 sha256 字段本身）。 */
    fun digest(): String = sha256Hex(canonical().toByteArray(Charsets.UTF_8))

    /** P0 完整性：声明 sha256 须与实算一致，否则拒部署（被篡改/损坏）。 */
    fun verifyIntegrity(): Boolean {
        if (sha256.isBlank()) return false
        return digest().equals(sha256, ignoreCase = true)
    }

    fun toJson(): String = JSONObject().apply {
        put("moduleId", moduleId)
        put("name", name)
        put("version", version)
        put("entry", entry)
        put("entryContent", entryContent)
        put("apkDeps", apkDeps.joinToString(","))
        put("pipDeps", pipDeps.joinToString(","))
        put("envProfiles", envProfiles.joinToString(","))
        put("env", JSONObject(env))
        put("ports", ports.joinToString(","))
        put("signature", signature)
        put("sha256", sha256)
    }.toString()

    companion object {
        fun fromJson(s: String): CmsDeployPackage {
            val o = JSONObject(s)
            val env = mutableMapOf<String, String>()
            o.optJSONObject("env")?.let { e ->
                e.keys().forEach { env[it] = e.getString(it) }
            }
            return CmsDeployPackage(
                moduleId = o.getString("moduleId"),
                name = o.optString("name", o.getString("moduleId")),
                version = o.optString("version", "1.0.0"),
                entry = o.optString("entry", "entry.py"),
                entryContent = o.optString("entryContent", ""),
                apkDeps = o.optString("apkDeps", "").split(",").map { it.trim() }.filter { it.isNotBlank() },
                pipDeps = o.optString("pipDeps", "").split(",").map { it.trim() }.filter { it.isNotBlank() },
                envProfiles = o.optString("envProfiles", "").split(",").map { it.trim() }.filter { it.isNotBlank() },
                env = env,
                ports = o.optString("ports", "").split(",").map { it.trim() }.filter { it.isNotBlank() }
                    .mapNotNull { it.toIntOrNull() },
                signature = o.optString("signature", ""),
                sha256 = o.optString("sha256", ""),
            )
        }

        /** 给包填上正确的 sha256（构建/发布内置包时用）。 */
        fun signed(pkg: CmsDeployPackage): CmsDeployPackage = pkg.copy(sha256 = pkg.digest())

        private fun sha256Hex(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(bytes).joinToString("") { "%02x".format(it) }
        }

        /** 内置示例：一个最小 python3 模块，用于端到端验证部署链路。 */
        fun samplePython(): CmsDeployPackage = signed(
            CmsDeployPackage(
                moduleId = "demo-py",
                name = "Python 演示模块",
                version = "1.0.0",
                entry = "entry.py",
                entryContent = "#!/usr/bin/env python3\n" +
                    "import sys\n" +
                    "print('hello from CMS terminal module')\n" +
                    "print('python', sys.version.split()[0])\n",
                apkDeps = listOf("python3"),
                pipDeps = emptyList(),
            )
        )

        /**
         * 由 [QuroCmsModule] 生成终端部署包（v192 修复：让「部署到终端」真正部署选中模块，
         * 而非恒部署内置 demo-py —— 此前 bug 导致模块自身部署状态永远不更新）。
         *
         * 规则：
         * - 提取模块的 terminal 类型能力，生成 entry.sh 依次执行其 action（终端命令）。
         * - 无 terminal 能力时退化为占位脚本（仅写入 manifest，状态仍标记 deployed，
         *   真实运行能力属 CMS v2 后续能力扩充范畴）。
         * - 包经 [signed] 填 sha256，满足部署前完整性校验（P0）。
         */
        fun fromModule(m: QuroCmsModule): CmsDeployPackage {
            // 若模块自带真实终端入口脚本（terminalEntry），直接作为 entry.sh 部署——
            // 真正实现「一键部署 CMS v2 系统构架到终端的包」（在 proot/Ubuntu 内可运行的后端），
            // 而非仅部署 manifest 的空壳。依赖按 LINUX(pip: 前缀→pip / 其余→apk) 与 ENV 分类装配。
            if (m.terminalEntry.isNotBlank()) {
                return signed(
                    CmsDeployPackage(
                        moduleId = m.id,
                        name = m.name.ifBlank { m.id },
                        version = m.version.ifBlank { "1.0.0" },
                        entry = "entry.sh",
                        entryContent = m.terminalEntry,
                        apkDeps = m.dependencies.filter { it.kind == DepKind.LINUX && !it.target().startsWith("pip") }.map { it.target() },
                        pipDeps = m.dependencies.filter { it.kind == DepKind.LINUX && it.target().startsWith("pip") }.map { it.target().removePrefix("pip:") },
                        envProfiles = m.dependencies.filter { it.kind == DepKind.ENV }.map { it.target() },
                    )
                )
            }
            // 兼容旧路径：仅当存在 terminal 类型能力时写真实命令，否则写占位脚本（仅部署 manifest）。
            val termCaps = m.capabilities.filter { it.actionType == "terminal" }
            val sb = StringBuilder()
            sb.appendLine("#!/bin/sh")
            sb.appendLine("# CMS module: ${m.id} (${m.name} v${m.version})")
            sb.appendLine("echo \"[cms] module ${m.id} deployed\"")
            if (termCaps.isNotEmpty()) {
                sb.appendLine("# ---- terminal capabilities ----")
                termCaps.forEach { c ->
                    sb.appendLine("# [${c.id}] ${c.summary}")
                    sb.appendLine(c.action)
                }
            } else {
                sb.appendLine("echo \"[cms] 无 terminal 能力，仅部署 manifest\"")
            }
            return signed(
                CmsDeployPackage(
                    moduleId = m.id,
                    name = m.name.ifBlank { m.id },
                    version = m.version.ifBlank { "1.0.0" },
                    entry = "entry.sh",
                    entryContent = sb.toString(),
                    apkDeps = emptyList(),
                    pipDeps = emptyList(),
                    envProfiles = m.dependencies.filter { it.kind == DepKind.ENV }.map { it.target() },
                )
            )
        }
    }
}
