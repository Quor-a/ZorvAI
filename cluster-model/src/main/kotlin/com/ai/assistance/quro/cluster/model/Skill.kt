package com.ai.assistance.quro.cluster.model

import kotlinx.serialization.Serializable

enum class SkillPermission {
    NONE, FS_SCOPED, NET_ALLOWLIST, TOOL_CALL, SYSTEM
}

/** SKILL.md 的 front-matter + 元数据 */
@Serializable
data class SkillManifest(
    val id: SkillId,
    val name: String,
    /** L1 摘要，约 100 token，常驻系统提示 */
    val description: String,
    val version: String = "1.0.0",
    val permissions: Set<SkillPermission> = emptySet(),
    val triggers: List<String> = emptyList(),
    val path: String = "",
    val checksum: String = "",
    val enabled: Boolean = true,
    val risk: RiskLevel = RiskLevel.LOW,
    val provenance: Provenance = Provenance.USER_CREATED
)

enum class SkillLevel { L1, L2, L3 }

data class SkillSnapshot(
    val manifest: SkillManifest,
    val level: SkillLevel,
    val body: String? = null,
    val resources: Map<String, String> = emptyMap()
)

data class ToolSpec(
    val id: ToolId,
    val name: String,
    val description: String,
    /** 参数 JSON Schema 字符串 */
    val jsonSchema: String,
    val risk: RiskLevel = RiskLevel.LOW,
    val timeoutMs: Long = 15_000L
)

data class ToolResult(
    val ok: Boolean,
    val output: String,
    val risk: RiskLevel = RiskLevel.LOW,
    val error: String? = null
)
