package com.ai.assistance.quro.core.mika.cap.sandbox

/**
 * 一条沙箱命令的结果。[output] 是合并后的标准输出与标准错误(最多 1 MB),
 * 更多的丢掉并计入 [droppedBytes];[killedBySystem]:被系统杀掉(多半是内存不足);[network]:运行时是否联网。
 */
data class CommandResult(
    val exitCode: Int, val timedOut: Boolean, val killedBySystem: Boolean, val durationMs: Long,
    val output: String, val droppedBytes: Long, val network: Boolean,
)
