package com.ai.assistance.quro.core.mika.agent

/** 提速开关的显式全关值；App 的首次默认值由 FeatureFlags 决定。 */
data class SpeedFlags(
    val inlineScreen: Boolean = false,
    val batchSteps: Boolean = false,
    val replay: Boolean = false,
    val deepLinks: Boolean = false,
    val lightSteps: Boolean = false,
    val parallelTasks: Boolean = false,
    /** 压缩时摘要写成结构化的四段并用真实用量校准估算(会改变模型所见,评测对比后再定)。 */
    val structuredCompaction: Boolean = false,
    /** 元素优先,只有读不清或模型主动请求时才给主模型画面。 */
    val adaptiveVision: Boolean = false,
) {
    private fun on(): Map<String, Boolean> = mapOf(
        "inlineScreen" to inlineScreen, "batchSteps" to batchSteps,
        "replay" to replay, "deepLinks" to deepLinks, "lightSteps" to lightSteps, "parallelTasks" to parallelTasks,
        "structuredCompaction" to structuredCompaction,
        "adaptiveVision" to adaptiveVision,
    )

    /** 打开的开关名按字母序用逗号连接;全关为 "none"。[parse] 可读回。 */
    fun describe(): String = on().filterValues { it }.keys.sorted().joinToString(",").ifEmpty { NONE }

    companion object {
        private const val NONE = "none"
        val OFF = SpeedFlags()
        private val KEYS = OFF.on().keys
        /** 已转正或撤掉的开关:存着的设置、评测参数里还可能有,读到就略过。 */
        private val RETIRED = setOf("cacheFriendlyPrune")

        /** "none"(或空)= 全关;否则逗号分隔的 `键=on|off` 或只写键名(= on)。未知键或值抛 [IllegalArgumentException]。 */
        fun parse(spec: String): SpeedFlags {
            val s = spec.trim()
            if (s.isEmpty() || s == NONE) return OFF
            val set = mutableMapOf<String, Boolean>()
            for (item in s.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val key = item.substringBefore('=').trim()
                val value = if ('=' in item) item.substringAfter('=').trim() else "on"
                if (key in RETIRED) continue
                require(key in KEYS) { "unknown speed flag: $key" }
                set[key] = when (value) {
                    "on" -> true
                    "off" -> false
                    else -> throw IllegalArgumentException("speed flag $key must be on or off, got $value")
                }
            }
            fun f(k: String) = set[k] ?: false
            return SpeedFlags(f("inlineScreen"), f("batchSteps"), f("replay"), f("deepLinks"), f("lightSteps"), f("parallelTasks"), f("structuredCompaction"), f("adaptiveVision"))
        }
    }
}
