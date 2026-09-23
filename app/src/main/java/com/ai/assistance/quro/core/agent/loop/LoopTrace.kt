package com.ai.assistance.quro.core.agent.loop

import com.ai.assistance.quro.core.agent.QuroAgentTrace

/**
 * 闭环轨迹发射器 —— 把"感知-判断-执行-反馈-校验-修正"六阶段显式打到 [QuroAgentTrace] 总线。
 *
 * 这样对话框内的「执行追踪」面板能清楚看到每个工具：是否真执行了、判断了什么、
 * 校验是否通过、失败后决定了重试/修正/回滚/升级中的哪一个。所有发射走 tryEmit，零风险。
 */
object LoopTrace {
    fun perceive(tag: String, summary: String, detail: String = "") = QuroAgentTrace.thought(tag, "感知·$summary", detail)
    fun judge(tag: String, summary: String, detail: String = "") = QuroAgentTrace.thought(tag, "判断·$summary", detail)
    fun act(tag: String, summary: String, detail: String = "") = QuroAgentTrace.action(tag, "执行·$summary", detail)
    fun feedback(tag: String, summary: String, detail: String = "") = QuroAgentTrace.result(tag, "反馈·$summary", detail)
    fun verify(tag: String, summary: String, detail: String = "") = QuroAgentTrace.result(tag, "校验·$summary", detail)
    fun correct(tag: String, summary: String, detail: String = "") = QuroAgentTrace.action(tag, "修正·$summary", detail)
    fun status(tag: String, summary: String, detail: String = "") = QuroAgentTrace.status(tag, summary, detail)
}
