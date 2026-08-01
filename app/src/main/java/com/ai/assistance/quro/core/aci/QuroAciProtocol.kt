package com.ai.assistance.quro.core.aci

/**
 * ACI 2.0 协议版本化（P0 协议内核种子，依托 aci-core 框架集成）。
 *
 * 协议版本独立于 ZorvAI 主程序版本，遵循 SemVer 语义：
 * - Minor（如 1.1）：向后兼容，仅新增可选字段/能力；
 * - Major（如 2.0）：不兼容变更，必须提供兼容适配器；
 * - 协商：两端比对支持的最高兼容版本。
 *
 * 本对象是主应用 ACI 层的一部分：受控端 QuroMainAciService 经 aci-core 的
 * Capability 机制把协议版本作为 `aci_protocol` 能力对外暴露；控制端
 * QuroAciManager 在拉取能力后做最简协商，结果写入协议映射并触发事件。
 * 当前 kernel 为 aci-protocol-v1，作为未来多版本共存的基础。
 */
object QuroAciProtocol {
    /** 当前协议标识（独立命名空间，不随 App 版本号变化） */
    const val PROTOCOL_VERSION = "aci-protocol-v1"
    /** 当前协议 SemVer */
    const val PROTOCOL_SEMVER = "1.0.0"

    /** 本端支持的全部协议版本（按新→旧） */
    val SUPPORTED: List<String> = listOf(PROTOCOL_VERSION)

    /**
     * 协商两端兼容的最高协议版本。
     * @param peer 对端声明支持的版本（逗号分隔或单一）
     * @return 双方都支持的最高版本；无交集返回 null（调用方应拒绝或降级）
     */
    fun negotiate(peer: String?): String? {
        val peerList = peer?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return PROTOCOL_VERSION
        for (v in SUPPORTED) if (v in peerList) return v
        return null
    }
}
