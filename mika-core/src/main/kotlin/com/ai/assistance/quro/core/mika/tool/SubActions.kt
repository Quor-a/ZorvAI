package com.ai.assistance.quro.core.mika.tool

import com.ai.assistance.quro.core.mika.model.Part

/**
 * 自己不动界面的工具(页面脚本)里的一个动作交回循环执行:守卫、锁屏关卡、不可逆判定、运行记录都和模型单独调用这个工具时一样。
 * 被拦下没执行时,结果里 error 为 confirmation_required / guard_stopped / target_unresolved,executed 为 false。
 */
fun interface SubActions {
    suspend fun run(call: Part.ToolCall): ToolExecution
}
