package com.ai.assistance.quro.core.chat

/**
 * 生成中再发一条消息，应该怎么办。
 *
 * 三种意图是真实存在且互斥的，不能只留一种：
 *  - [INTERRUPT]：我改主意了，别再写了，按新的来。（默认，也是加这个开关之前的行为）
 *  - [QUEUE]：别打断，把我这条排在后面，这轮完了接着处理。
 *  - [STEER]：别打断，也别等——把这条插进当前这一轮的上下文里，
 *              模型下次要调工具（或下一跳请求）时就会读到，并据此调整方向。
 *
 * 存的是稳定 key（不是 ordinal），因为要落 SharedPreferences：枚举顺序一变，
 * ordinal 就会把「引导」读成「排队」，这种错很难被用户描述清楚。
 */
enum class BusySendMode(val key: String) {

    INTERRUPT("interrupt"),
    QUEUE("queue"),
    STEER("steer");

    companion object {

        /** 历史行为：一有输入就打断上一轮。保持默认，不做静默变更。 */
        val DEFAULT: BusySendMode = INTERRUPT

        /** 容错解析：未知值（旧版本残留 / 手改配置）一律回落到默认，绝不抛异常。 */
        fun fromKey(raw: String?): BusySendMode {
            val k = raw?.trim()?.lowercase() ?: return DEFAULT
            if (k.isEmpty()) return DEFAULT
            return values().firstOrNull { it.key == k } ?: DEFAULT
        }
    }
}

/**
 * 「引导消息」是否还没被回答。
 *
 * 引导的语义是「插进当前轮」：只要模型在它之后又产出了一条 assistant 消息，
 * 就说明这条引导已经被这一轮读到了，不需要再补一轮。
 * 若它之后一条 assistant 都没有，说明模型恰好在注入前就收尾了——
 * 这条引导就成了「没人接的话」，必须补跑一轮，否则用户会看到自己说了话却石沉大海。
 */
object SteerPolicy {

    /**
     * @param roles 会话缓冲里按顺序排列的 role 序列（user / assistant / tool / system）
     * @param steerAt 那条引导消息在 [roles] 中的下标；越界或负数视为无效，不补跑
     */
    fun needsResume(roles: List<String>, steerAt: Int): Boolean {
        if (steerAt < 0 || steerAt >= roles.size) return false
        return roles.drop(steerAt + 1).none { it == "assistant" }
    }
}
