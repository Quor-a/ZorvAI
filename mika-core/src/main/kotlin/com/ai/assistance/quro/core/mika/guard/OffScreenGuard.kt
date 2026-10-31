package com.ai.assistance.quro.core.mika.guard

import com.ai.assistance.quro.core.mika.model.Part
import kotlinx.serialization.json.JsonObject

/** 不动屏幕的工具(沙箱一类)的守卫,每个任务一份;屏幕守卫不看这些工具。 */
interface OffScreenGuard {
    /** 叫停的结论;没叫停为 null。 */
    val stop: GuardVerdict?

    /** 这类工具的额度用完时代替执行的结果;没用完为 null。 */
    fun exhausted(tool: String): JsonObject?

    /** 执行前按剩下的额度调整调用;不用调整原样返回。 */
    fun capTimeout(call: Part.ToolCall, args: JsonObject): Part.ToolCall

    /** 执行后的结论。 */
    fun result(tool: String, args: JsonObject, content: JsonObject): List<GuardVerdict>
}
