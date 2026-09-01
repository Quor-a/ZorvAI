package com.ai.assistance.quro.libaci

import ai.aidl.aci.core.Capability

/**
 * 能力声明数据类（与 aci-core 的 Capability 解耦，方便按业务域纯 Kotlin 描述）。
 *
 * - id：动作 id，进 ZorvAI 的 prompt tools 段，LLM 靠它决定调哪个能力。
 *   主应用统一用 `main.` 前缀，避免与副应用（`sub.` 前缀）能力撞 id 导致 LLM 调错。
 * - desc：写给模型看的，不是给人看的。要点：何时用 + 参数是否必填 + 返回什么 + 有无副作用。
 * - dangerous：高危能力标记（破坏性写操作），触发 PermissionGuard 的二次确认钩子。
 */
data class CapabilitySpec(
    val id: String,
    val desc: String,
    val dangerous: Boolean = false
) {
    /** 转为 aci-core 的 Capability（仅携带 id + desc；参数 schema 由 LLM 读 desc 推断）。 */
    fun toCapability(): Capability = Capability.create(id, desc)
}
