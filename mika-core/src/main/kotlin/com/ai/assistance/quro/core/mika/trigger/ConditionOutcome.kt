package com.ai.assistance.quro.core.mika.trigger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 条件检查的机器结果；无法解析和执行失败都不能当成条件不成立。 */
sealed interface ConditionOutcome {
    data class Checked(val met: Boolean, val evidence: String?) : ConditionOutcome
    data class Error(val reason: String) : ConditionOutcome

    companion object {
        fun parse(answer: String): ConditionOutcome {
            val raw = answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val body = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return Error("条件检查没有返回可读取的结果")
            val met = (body["met"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: return Error("条件检查结果缺少 met 状态")
            val evidence = (body["evidence"] as? JsonPrimitive)?.content?.take(500)
            return Checked(met, evidence)
        }
    }
}
