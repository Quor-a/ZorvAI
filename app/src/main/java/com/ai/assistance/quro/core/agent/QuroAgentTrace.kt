package com.ai.assistance.quro.core.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/**
 * AI 行动轨迹总线（原创，对应「终端即 AI 思考+行动可视化」的纯净架构）。
 *
 * 旧的终端是真实 PTY / Shizuku / proot 执行通道；新版架构下 AI 是纯粹的应用内执行体，
 * 不通过命令/无障碍/Root 控制系统。本对象是 AI 各路径（工具调度、语音播报、思考输出等）
 * 统一发射「思考 / 行动 / 结果 / 状态」事件的事件总线，供对话框内嵌「执行追踪」面板订阅并可视化，
 * 让终端变成一个只读的「AI 在想什么、做了什么」的时间流，而不是真正的 shell。
 *
 * 设计约束：
 * - 所有发射都走 [tryEmit]，绝不挂起、绝不抛异常，保证插桩点不会拖慢或打断主流程。
 * - 订阅者迟到也能拿到最近事件（replay 缓冲），UI 重绘不丢历史。
 */
object QuroAgentTrace {

    /** 事件种类。 */
    enum class TraceKind { THOUGHT, ACTION, RESULT, STATUS }

    /** 单条轨迹事件。 */
    data class AgentTraceEvent(
        val kind: TraceKind,
        val tag: String,
        val summary: String,
        val detail: String = "",
        val ts: Long = System.currentTimeMillis(),
        /** 稳定唯一 id：发射时生成，UI 去重与 LazyColumn key 使用，避免重复/跨会话污染。 */
        val id: String = UUID.randomUUID().toString(),
    )

    private val _flow = MutableSharedFlow<AgentTraceEvent>(replay = 128, extraBufferCapacity = 256)

    /** 对外只读流；UI 订阅此流渲染 AI 行动流。 */
    val flow: SharedFlow<AgentTraceEvent> = _flow.asSharedFlow()

    /** 发射一条思考（模型内部推理 / 规划）。 */
    fun thought(tag: String, summary: String, detail: String = "") =
        emit(TraceKind.THOUGHT, tag, summary, detail)

    /** 发射一条行动（AI 决定要做的事，例如调用某工具 / 播报语音）。 */
    fun action(tag: String, summary: String, detail: String = "") =
        emit(TraceKind.ACTION, tag, summary, detail)

    /** 发射一条结果（行动产出的返回值 / 结论）。 */
    fun result(tag: String, summary: String, detail: String = "") =
        emit(TraceKind.RESULT, tag, summary, detail)

    /** 发射一条状态（生命周期 / 连接 / 错误等元信息）。 */
    fun status(tag: String, summary: String, detail: String = "") =
        emit(TraceKind.STATUS, tag, summary, detail)

    /** 安全发射：任何异常都被吞掉，绝不外抛，保证插桩点零风险。 */
    private fun emit(kind: TraceKind, tag: String, summary: String, detail: String) {
        runCatching { _flow.tryEmit(AgentTraceEvent(kind, tag, summary, detail)) }
    }
}
