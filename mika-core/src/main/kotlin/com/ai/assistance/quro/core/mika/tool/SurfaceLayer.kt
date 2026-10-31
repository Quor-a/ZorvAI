package com.ai.assistance.quro.core.mika.tool

import kotlinx.serialization.json.JsonObject

/**
 * 有界面的能力(手机、网页)共用的那一层在每次调用前后做的事:在操作哪块屏、动作前后看一眼报实际效果、附上动作之后的界面、
 * 等界面稳定、此刻的样子。分发器只经这个接口用到它,不认识读屏、元素、编号这些界面状态;具体实现由装配层在开局给。
 * 屏用能力的 id 指。
 */
internal interface SurfaceLayer {
    /** 此刻在操作的那块屏;没有调用在进行时是最近一次成功操作的那块,任务开始时是第一块。 */
    val current: String

    /** 一次调用开始,作用在 [id] 那块屏上;不是屏或没给时沿用此刻那块。 */
    fun enter(id: String?)

    /** 那次调用结束;[succeeded] 时它那块屏成为最近操作的屏。 */
    fun leave(succeeded: Boolean)

    /** 这个工具作用在哪块屏上;不是屏幕工具为 null。 */
    fun surfaceOf(tool: AgentTool?): String?

    /** 用另一块屏的编号操作这块屏的工具时给模型的结果(不执行);认不出或没用错为 null。 */
    fun misaimed(tool: AgentTool, args: JsonObject, tools: ToolIndex): ToolExecution?

    /** 这次调用不是界面上的(或没走到 [around]):上一次留下的动作目标不留。 */
    fun skip()

    /**
     * 包住一次工具执行:之前做界面上的准备,[body] 拿到「这次由界面一侧报实际效果」与否,之后做界面上的收尾
     * (按 [inlineScreen] 附界面差异、附画面、报效果、清掉看图得来的点)。
     */
    suspend fun around(tool: AgentTool, args: JsonObject, inlineScreen: Boolean, body: suspend (reportsEffect: Boolean) -> ToolExecution): ToolExecution

    /** 开着时写操作执行前取目标元素与锚点(回放开关打开时由循环设置)。 */
    var captureTargets: Boolean

    /** 运行记录用:模型编号此刻对应的端口编号;没有为空。 */
    fun portRecord(tool: AgentTool?, args: JsonObject): JsonObject

    /** 模型此刻还看得到哪些读取(按编号)。 */
    fun setVisible(ids: Set<String>)

    /** 等最近操作的那块屏稳定,最多 [timeoutMs];读不到状态也照常返回。 */
    suspend fun settle(timeoutMs: Long)

    /** 最近操作的那块屏此刻看得到的字(每个元素一条,不登记编号);读不到为空。 */
    suspend fun visibleText(): List<String>

    /**
     * 在最近操作的那块屏上等到 [until] 成立,或看出意外、到了时限、[interrupted] 为真(用户插了话)就不再等;
     * 时间按 [clock] 算。读屏不登记编号。
     */
    suspend fun waitUntil(until: Until, clock: () -> Long, interrupted: () -> Boolean): UntilOutcome

    /** 最近操作的那块屏此刻的一帧和它的画面编号;受保护、截不到为 null。 */
    suspend fun imageNow(): Pair<ByteArray, Int>?

    /** 此刻的样子(内容和画面同一时刻);什么都没打开时为 null。观察还没记进账本。 */
    suspend fun viewNow(): ToolDispatcher.Glance?

    /** 此刻那个地方的版本(网页的手机版、电脑版);没有或读不到为 null。 */
    suspend fun mode(): String?
}
