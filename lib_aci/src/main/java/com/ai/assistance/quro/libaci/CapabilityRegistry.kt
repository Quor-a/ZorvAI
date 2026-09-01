package com.ai.assistance.quro.libaci

/**
 * 能力注册中心（单例）。
 *
 * 设计：框架层持有注册表，:cap_main 在运行时把各业务 Handler 注册进来，
 * AciRouter / MainAciService 只认这个表，不反向依赖 :cap_main，避免模块循环依赖。
 *
 * - id 全局唯一，主应用统一 `main.` 前缀（与副应用 `sub.` 区分），防止 LLM 调错能力。
 * - 注册幂等：同 id 重复注册会覆盖（最后一次生效），便于热更新/测试。
 */
object CapabilityRegistry {
    private val handlers = LinkedHashMap<String, AciHandler>()

    /** 注册一个能力 Handler。 */
    fun register(handler: AciHandler) {
        handlers[handler.spec.id] = handler
    }

    /** 按能力 id 取 Handler；未知 id 返回 null（AciRouter 会映射成 CAPABILITY_NOT_FOUND）。 */
    fun get(id: String?): AciHandler? = if (id == null) null else handlers[id]

    /** 全部已注册 Handler（onCreateCapabilities 遍历此列表生成能力声明）。 */
    fun all(): List<AciHandler> = handlers.values.toList()

    /** 全部已注册能力 id（调试 / 日志用）。 */
    fun ids(): List<String> = handlers.keys.toList()

    /** 清空（仅测试用）。 */
    fun clear() = handlers.clear()
}
